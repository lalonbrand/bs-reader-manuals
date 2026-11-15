package com.example.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ExtractionResult(
    val bookDirectory: File,
    val pageCount: Int,
    val coverFilePath: String?,
    val totalExtractedBytes: Long
)

object ZipBookExtractor {
    private val supportedImageExtensions = setOf("webp", "png", "jpg", "jpeg")

    suspend fun extractFromStream(
        zipStream: InputStream,
        targetBookDir: File,
        onProgress: (extractedCount: Int) -> Unit = {}
    ): ExtractionResult = withContext(Dispatchers.IO) {
        if (targetBookDir.exists()) {
            targetBookDir.deleteRecursively()
        }
        targetBookDir.mkdirs()

        val stagingDir = File(targetBookDir, "_staging")
        stagingDir.mkdirs()
        val stagingCanonical = stagingDir.canonicalPath

        var extractedCount = 0
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(zipStream)).use { zis ->
            val buffer = ByteArray(16 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val baseName = File(entryName).name
                val isMacJunk = entryName.startsWith("__MACOSX/") || baseName.startsWith("._")

                if (!entry.isDirectory && !isMacJunk && isSupportedImage(baseName)) {
                    val outFile = File(stagingDir, entryName)
                    val outCanonical = outFile.canonicalPath
                    if (!outCanonical.startsWith(stagingCanonical + File.separator)) {
                        throw SecurityException("Blocked unsafe zip path traversal: $entryName")
                    }
                    outFile.parentFile?.mkdirs()

                    FileOutputStream(outFile).use { fos ->
                        var read = zis.read(buffer)
                        while (read != -1) {
                            fos.write(buffer, 0, read)
                            totalBytes += read
                            read = zis.read(buffer)
                        }
                    }
                    extractedCount++
                    onProgress(extractedCount)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Collect all extracted images, sort naturally, and flatten into targetBookDir
        val allImages = stagingDir.walkTopDown()
            .filter { it.isFile && isSupportedImage(it.name) }
            .toList()

        val dedicatedCover = allImages.firstOrNull {
            it.nameWithoutExtension.equals("cover", ignoreCase = true)
        }

        val pageCandidates = allImages
            .filter { it != dedicatedCover || allImages.size == 1 }
            .sortedWith(NaturalFilenameComparator)

        if (pageCandidates.isEmpty()) {
            targetBookDir.deleteRecursively()
            throw IllegalStateException("Archive does not contain any WebP or image pages.")
        }

        var finalCoverPath: String? = null
        if (dedicatedCover != null && pageCandidates.none { it == dedicatedCover }) {
            val destCover = File(targetBookDir, "cover.${dedicatedCover.extension.lowercase()}")
            dedicatedCover.copyTo(destCover, overwrite = true)
            finalCoverPath = destCover.absolutePath
        }

        pageCandidates.forEachIndexed { index, sourceFile ->
            val ext = sourceFile.extension.lowercase()
            val normalizedName = "page_%03d.%s".format(index + 1, ext)
            val destFile = File(targetBookDir, normalizedName)
            sourceFile.copyTo(destFile, overwrite = true)
            if (index == 0 && finalCoverPath == null) {
                finalCoverPath = destFile.absolutePath
            }
        }

        stagingDir.deleteRecursively()

        ExtractionResult(
            bookDirectory = targetBookDir,
            pageCount = pageCandidates.size,
            coverFilePath = finalCoverPath,
            totalExtractedBytes = totalBytes
        )
    }

    private fun isSupportedImage(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in supportedImageExtensions
    }
}

object NaturalFilenameComparator : Comparator<File> {
    private val chunkRegex = Regex("(\\d+|\\D+)")

    override fun compare(a: File, b: File): Int {
        val partsA = chunkRegex.findAll(a.name.lowercase()).map { it.value }.toList()
        val partsB = chunkRegex.findAll(b.name.lowercase()).map { it.value }.toList()
        val count = minOf(partsA.size, partsB.size)

        for (i in 0 until count) {
            val pA = partsA[i]
            val pB = partsB[i]
            val nA = pA.toLongOrNull()
            val nB = pB.toLongOrNull()
            val diff = if (nA != null && nB != null) {
                nA.compareTo(nB)
            } else {
                pA.compareTo(pB)
            }
            if (diff != 0) return diff
        }
        return partsA.size.compareTo(partsB.size)
    }
}
