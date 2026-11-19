package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.BookDao
import com.example.data.local.BookEntity
import com.example.data.local.BookStatus
import com.example.data.local.DocumentNotification
import com.example.data.local.SettingsDataStore
import com.example.data.remote.CatalogApi
import com.example.data.remote.NetworkModule
import com.example.data.storage.ZipBookExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TransferProgress(
    val phase: TransferPhase,
    val progress: Float?, // null = indeterminate
    val detailLabel: String
)

enum class TransferPhase {
    DOWNLOADING,
    EXTRACTING
}

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val settingsStore: SettingsDataStore = SettingsDataStore(context),
    private val catalogApi: CatalogApi = NetworkModule.catalogApi
) {
    private val _transfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferProgress>> = _transfers.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    val booksFlow: Flow<List<BookEntity>> = bookDao.observeAllBooks()

    fun observeBook(bookId: String): Flow<BookEntity?> = bookDao.observeBookById(bookId)

    suspend fun getBook(bookId: String): BookEntity? = bookDao.getBookById(bookId)

    suspend fun removeStarterBookIfPresent() = withContext(Dispatchers.IO) {
        val starterId = "celestial-almanac-01"
        val existing = bookDao.getBookById(starterId)
        if (existing != null) {
            existing.localDirPath?.let { path -> File(path).deleteRecursively() }
            bookDao.deleteBookById(starterId)
        }
        val legacyDir = File(context.filesDir, "books/$starterId")
        if (legacyDir.exists()) {
            legacyDir.deleteRecursively()
        }
    }

    suspend fun syncRemoteCatalog(catalogUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val separator = if ('?' in catalogUrl) "&" else "?"
            val freshUrl = "$catalogUrl${separator}_ts=${System.currentTimeMillis()}"
            val body = catalogApi.fetchCatalogRaw(freshUrl)
            val rawJson = body.string()
            val catalog = NetworkModule.parseCatalogJson(rawJson)

            val incomingIds = catalog.books.map { it.id }.toSet()
            val existingBooks = bookDao.getAllBooks()
            for (existing in existingBooks) {
                if (!existing.zipUrl.isNullOrBlank() && existing.id !in incomingIds) {
                    cancelDownload(existing.id)
                    existing.localDirPath?.let { path -> File(path).deleteRecursively() }
                    existing.localCoverPath?.let { path -> File(path).delete() }
                    bookDao.deleteBookById(existing.id)
                }
            }

            val entities = catalog.books.map { dto ->
                val existing = bookDao.getBookById(dto.id)
                val keepReady = existing?.isReady == true
                BookEntity(
                    id = dto.id,
                    title = dto.title,
                    author = dto.author,
                    description = dto.description,
                    category = dto.category,
                    coverUrl = dto.coverUrl,
                    localCoverPath = existing?.localCoverPath,
                    zipUrl = dto.zipUrl,
                    localDirPath = existing?.localDirPath,
                    pageCount = if (keepReady) existing!!.pageCount else dto.pageCount,
                    lastReadPage = existing?.lastReadPage ?: 0,
                    fileSizeBytes = if (keepReady) existing!!.fileSizeBytes else dto.fileSizeBytes,
                    status = if (keepReady) BookStatus.READY.name else BookStatus.NOT_DOWNLOADED.name,
                    errorMessage = null,
                    isStarterBundled = existing?.isStarterBundled ?: false,
                    updatedAt = existing?.updatedAt ?: System.currentTimeMillis()
                )
            }
            if (entities.isNotEmpty()) {
                bookDao.upsertBooks(entities)
            }

            val now = System.currentTimeMillis()
            val candidateNotifications = catalog.books.mapIndexed { idx, dto ->
                val resolvedPdfName = dto.pdfFileName?.takeIf { it.isNotBlank() }
                    ?: "${dto.title.trim().ifBlank { dto.id }}.pdf"
                DocumentNotification(
                    id = "notif_${dto.id}_$now",
                    bookId = dto.id,
                    pdfFileName = resolvedPdfName,
                    title = dto.title,
                    sourceLabel = "Remote Catalog",
                    timestampMillis = now + (catalog.books.size - idx),
                    isUnread = true
                )
            }
            settingsStore.recordNewlyAddedDocuments(candidateNotifications)

            entities.size
        }
    }

    suspend fun downloadAndExtractBook(book: BookEntity) {
        val zipUrl = book.zipUrl ?: return
        if (activeJobs.containsKey(book.id)) return

        coroutineScope {
            val job = launch(Dispatchers.IO) {
                val tempZip = File(context.cacheDir, "download_${book.id}.zip")
                try {
                    bookDao.updateStatus(book.id, BookStatus.DOWNLOADING.name, null)
                    updateTransfer(
                        book.id,
                        TransferProgress(TransferPhase.DOWNLOADING, 0f, "Connecting...")
                    )

                    val responseBody = catalogApi.downloadZip(zipUrl)
                    val totalBytes = responseBody.contentLength()

                    responseBody.byteStream().use { input ->
                        FileOutputStream(tempZip).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead = 0L
                            var read = input.read(buffer)
                            while (read != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                val fraction = if (totalBytes > 0) {
                                    (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                val kbRead = bytesRead / 1024
                                val label = if (totalBytes > 0) {
                                    "$kbRead KB / ${totalBytes / 1024} KB"
                                } else {
                                    "$kbRead KB downloaded"
                                }
                                updateTransfer(
                                    book.id,
                                    TransferProgress(TransferPhase.DOWNLOADING, fraction, label)
                                )
                                read = input.read(buffer)
                            }
                        }
                    }

                    bookDao.updateStatus(book.id, BookStatus.EXTRACTING.name, null)
                    updateTransfer(
                        book.id,
                        TransferProgress(TransferPhase.EXTRACTING, null, "Extracting WebP sheets...")
                    )

                    val targetDir = File(context.filesDir, "books/${sanitizeId(book.id)}")
                    val extraction = FileInputStream(tempZip).use { fis ->
                        ZipBookExtractor.extractFromStream(fis, targetDir) { count ->
                            updateTransfer(
                                book.id,
                                TransferProgress(
                                    TransferPhase.EXTRACTING,
                                    null,
                                    "Extracted $count WebP pages..."
                                )
                            )
                        }
                    }

                    bookDao.markBookExtracted(
                        bookId = book.id,
                        localDirPath = extraction.bookDirectory.absolutePath,
                        localCoverPath = extraction.coverFilePath,
                        pageCount = extraction.pageCount,
                        fileSizeBytes = extraction.totalExtractedBytes
                    )
                } catch (_: CancellationException) {
                    bookDao.updateStatus(book.id, BookStatus.NOT_DOWNLOADED.name, null)
                } catch (e: Exception) {
                    bookDao.updateStatus(
                        book.id,
                        BookStatus.ERROR.name,
                        e.localizedMessage ?: "Download or extraction failed"
                    )
                } finally {
                    tempZip.delete()
                    clearTransfer(book.id)
                    activeJobs.remove(book.id)
                }
            }
            activeJobs[book.id] = job
            job.join()
        }
    }

    fun cancelDownload(bookId: String) {
        activeJobs[bookId]?.cancel()
        activeJobs.remove(bookId)
        clearTransfer(bookId)
    }

    suspend fun importLocalZip(uri: Uri): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(uri) ?: "Imported Codex"
            val cleanTitle = displayName
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifBlank { "Imported Codex" }
            val bookId = "local-${UUID.randomUUID().toString().take(8)}"
            val targetDir = File(context.filesDir, "books/$bookId")

            updateTransfer(
                bookId,
                TransferProgress(TransferPhase.EXTRACTING, null, "Extracting local archive...")
            )

            val extraction = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipBookExtractor.extractFromStream(stream, targetDir) { count ->
                        updateTransfer(
                            bookId,
                            TransferProgress(TransferPhase.EXTRACTING, null, "Extracted $count pages...")
                        )
                    }
                } ?: throw IllegalStateException("Unable to open selected .zip file.")
            } finally {
                clearTransfer(bookId)
            }

            val book = BookEntity(
                id = bookId,
                title = cleanTitle,
                author = "Local Archive (.zip)",
                description = "Imported directly from device storage with ${extraction.pageCount} pre-rasterized sheets.",
                category = "Local Import",
                localCoverPath = extraction.coverFilePath,
                localDirPath = extraction.bookDirectory.absolutePath,
                pageCount = extraction.pageCount,
                lastReadPage = 0,
                fileSizeBytes = extraction.totalExtractedBytes,
                status = BookStatus.READY.name,
                updatedAt = System.currentTimeMillis()
            )
            bookDao.upsertBook(book)
            settingsStore.addNotification(
                DocumentNotification(
                    id = "notif_${bookId}_${System.currentTimeMillis()}",
                    bookId = bookId,
                    pdfFileName = displayName,
                    title = cleanTitle,
                    sourceLabel = "Local Import",
                    timestampMillis = System.currentTimeMillis(),
                    isUnread = true
                )
            )
            book
        }
    }

    suspend fun updateReadingProgress(bookId: String, pageIndex: Int) {
        bookDao.updateLastReadPage(bookId, pageIndex, System.currentTimeMillis())
    }

    suspend fun deleteLocalFiles(book: BookEntity) = withContext(Dispatchers.IO) {
        book.localDirPath?.let { path ->
            File(path).deleteRecursively()
        }
        if (book.zipUrl != null) {
            bookDao.upsertBook(
                book.copy(
                    localDirPath = null,
                    localCoverPath = null,
                    lastReadPage = 0,
                    status = BookStatus.NOT_DOWNLOADED.name,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            bookDao.deleteBookById(book.id)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun updateTransfer(bookId: String, progress: TransferProgress) {
        _transfers.update { it + (bookId to progress) }
    }

    private fun clearTransfer(bookId: String) {
        _transfers.update { it - bookId }
    }
}
