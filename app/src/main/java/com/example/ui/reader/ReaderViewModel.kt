package com.example.ui.reader

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.PageTranslationService
import com.example.data.firebase.ensureFirebaseInitialized
import com.example.data.firebase.getFirebaseInitDiagnosticMessage
import com.example.data.local.BookEntity
import com.example.data.local.BookTranslationLoader
import com.example.data.local.FolioDatabase
import com.example.data.local.ReaderSurfaceTone
import com.example.data.local.SettingsDataStore
import com.example.data.local.TranslatedLine
import com.example.data.repository.BookRepository
import com.example.data.storage.WebpPageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderUiState(
    val book: BookEntity? = null,
    val pageFiles: List<File> = emptyList(),
    val currentPageIndex: Int = 0,
    val currentBitmap: ImageBitmap? = null,
    val nextBitmap: ImageBitmap? = null,
    val prevBitmap: ImageBitmap? = null,
    val pageAspectRatio: Float = 1080f / 1520f,
    val surfaceTone: ReaderSurfaceTone = ReaderSurfaceTone.PAPER,
    val showPageOverlay: Boolean = true,
    val pageSoundEnabled: Boolean = true,
    val banglaOnlyMode: Boolean = true,
    val translationsByPage: Map<Int, List<TranslatedLine>> = emptyMap(),
    val translatingPages: Set<Int> = emptySet(),
    val translationErrorsByPage: Map<Int, String> = emptyMap(),
    val checkedEmptyPages: Set<Int> = emptySet(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class ReaderViewModel(
    application: Application,
    private val bookId: String,
    private val repository: BookRepository,
    private val settingsStore: SettingsDataStore,
    private val pageCache: WebpPageCache = WebpPageCache(),
    private val pageTranslationService: PageTranslationService = PageTranslationService()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.preferences.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        surfaceTone = prefs.surfaceTone,
                        showPageOverlay = prefs.showPageOverlay,
                        pageSoundEnabled = prefs.pageSoundEnabled,
                        banglaOnlyMode = prefs.banglaOnlyMode
                    )
                }
            }
        }
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val book = repository.getBook(bookId)
            val dirPath = book?.localDirPath
            if (book == null || dirPath.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Local WebP directory not found. Download or re-extract the book."
                    )
                }
                return@launch
            }

            val files = withContext(Dispatchers.IO) {
                pageCache.getOrderedPageFiles(dirPath)
            }
            if (files.isEmpty()) {
                _uiState.update {
                    it.copy(
                        book = book,
                        isLoading = false,
                        errorMessage = "No pre-rasterized WebP pages found in ${book.localDirPath}."
                    )
                }
                return@launch
            }

            val aspect = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(files.first().absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.45f, 1.6f)
                } else {
                    1080f / 1520f
                }
            }

            val translations = withContext(Dispatchers.IO) {
                BookTranslationLoader.loadByOneBasedPage(dirPath)
            }

            val initialPage = book.lastReadPage.coerceIn(0, files.lastIndex)
            _uiState.update {
                it.copy(
                    book = book,
                    pageFiles = files,
                    currentPageIndex = initialPage,
                    pageAspectRatio = aspect,
                    translationsByPage = translations,
                    isLoading = false
                )
            }
            loadTriplet(files, initialPage)
        }
    }

    fun goToPage(targetIndex: Int) {
        val state = _uiState.value
        val files = state.pageFiles
        if (files.isEmpty()) return
        val clamped = targetIndex.coerceIn(0, files.lastIndex)
        if (clamped == state.currentPageIndex && state.currentBitmap != null) return

        val immediateCurr = files.getOrNull(clamped)?.let { pageCache.getCached(it) }
            ?: when (clamped) {
                state.currentPageIndex + 1 -> state.nextBitmap
                state.currentPageIndex - 1 -> state.prevBitmap
                else -> state.currentBitmap
            }
        val immediateNext = files.getOrNull(clamped + 1)?.let { pageCache.getCached(it) }
            ?: if (clamped == state.currentPageIndex - 1) state.currentBitmap else null
        val immediatePrev = files.getOrNull(clamped - 1)?.let { pageCache.getCached(it) }
            ?: if (clamped == state.currentPageIndex + 1) state.currentBitmap else null

        _uiState.update {
            it.copy(
                currentPageIndex = clamped,
                currentBitmap = immediateCurr ?: it.currentBitmap,
                nextBitmap = immediateNext,
                prevBitmap = immediatePrev
            )
        }
        viewModelScope.launch {
            repository.updateReadingProgress(bookId, clamped)
        }
        loadTriplet(files, clamped)
    }

    fun translateCurrentPageIfNeeded(forceRefresh: Boolean = false) {
        val oneBasedPage = _uiState.value.currentPageIndex + 1
        translatePageIfNeeded(oneBasedPage = oneBasedPage, forceRefresh = forceRefresh)
    }

    fun translatePageIfNeeded(oneBasedPage: Int, forceRefresh: Boolean = false) {
        val state = _uiState.value
        val bookDir = state.book?.localDirPath ?: return
        val pageFile = state.pageFiles.getOrNull(oneBasedPage - 1) ?: return

        if (!forceRefresh && (state.translationsByPage[oneBasedPage]?.isNotEmpty() == true || oneBasedPage in state.checkedEmptyPages)) {
            return
        }
        if (oneBasedPage in state.translatingPages) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    translatingPages = it.translatingPages + oneBasedPage,
                    translationErrorsByPage = it.translationErrorsByPage - oneBasedPage
                )
            }

            try {
                if (!ensureFirebaseInitialized(getApplication())) {
                    throw IllegalStateException(getFirebaseInitDiagnosticMessage(getApplication()))
                }
                val paragraphs = pageTranslationService.translatePageImage(pageFile)
                if (paragraphs.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        BookTranslationLoader.saveCachedPageTranslation(bookDir, oneBasedPage, paragraphs)
                    }
                    _uiState.update {
                        it.copy(
                            translationsByPage = it.translationsByPage + (oneBasedPage to paragraphs),
                            checkedEmptyPages = it.checkedEmptyPages - oneBasedPage,
                            translatingPages = it.translatingPages - oneBasedPage,
                            translationErrorsByPage = it.translationErrorsByPage - oneBasedPage
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            checkedEmptyPages = it.checkedEmptyPages + oneBasedPage,
                            translatingPages = it.translatingPages - oneBasedPage
                        )
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(translatingPages = it.translatingPages - oneBasedPage) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        translatingPages = it.translatingPages - oneBasedPage,
                        translationErrorsByPage = it.translationErrorsByPage + (
                            oneBasedPage to (e.localizedMessage ?: "Failed to translate page $oneBasedPage.")
                            )
                    )
                }
            }
        }
    }

    fun setSurfaceTone(tone: ReaderSurfaceTone) {
        viewModelScope.launch {
            settingsStore.setSurfaceTone(tone)
        }
    }

    fun setShowPageOverlay(show: Boolean) {
        viewModelScope.launch {
            settingsStore.setShowPageOverlay(show)
        }
    }

    fun setPageSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPageSoundEnabled(enabled)
        }
    }

    fun setBanglaOnlyMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setBanglaOnlyMode(enabled)
        }
    }

    suspend fun loadThumbnail(file: File): ImageBitmap? {
        return pageCache.loadPage(file, maxDimensionPx = 360)
    }

    private fun loadTriplet(files: List<File>, index: Int) {
        pageCache.prefetchAround(viewModelScope, files, index)
        viewModelScope.launch {
            val curr = files.getOrNull(index)?.let { pageCache.loadPage(it) }
            val next = files.getOrNull(index + 1)?.let { pageCache.loadPage(it) }
            val prev = files.getOrNull(index - 1)?.let { pageCache.loadPage(it) }
            _uiState.update {
                if (it.currentPageIndex == index) {
                    it.copy(
                        currentBitmap = curr,
                        nextBitmap = next,
                        prevBitmap = prev
                    )
                } else {
                    it
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pageCache.clear()
    }

    class Factory(
        private val application: Application,
        private val bookId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val db = FolioDatabase.getInstance(application)
            val repo = BookRepository(application, db.bookDao())
            val settings = SettingsDataStore(application)
            return ReaderViewModel(application, bookId, repo, settings) as T
        }
    }
}
