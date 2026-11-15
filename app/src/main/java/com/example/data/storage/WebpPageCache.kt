package com.example.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class WebpPageCache {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    private val cacheSizeKb = (maxMemoryKb / 6).coerceIn(12 * 1024, 48 * 1024)

    private val lruCache = object : LruCache<String, CachedPage>(cacheSizeKb) {
        override fun sizeOf(key: String, value: CachedPage): Int = value.sizeKb
    }

    private val decodeMutex = Mutex()

    private data class CachedPage(
        val imageBitmap: ImageBitmap,
        val sizeKb: Int
    )

    fun getOrderedPageFiles(bookDirPath: String): List<File> {
        val dir = File(bookDirPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    !file.nameWithoutExtension.equals("cover", ignoreCase = true) &&
                    file.extension.lowercase() in setOf("webp", "png", "jpg", "jpeg")
            }
            ?.sortedWith(NaturalFilenameComparator)
            ?: emptyList()
    }

    fun getCached(file: File, maxDimensionPx: Int = 2048): ImageBitmap? {
        return lruCache.get(cacheKey(file, maxDimensionPx))?.imageBitmap
    }

    suspend fun loadPage(file: File, maxDimensionPx: Int = 2048): ImageBitmap? {
        val key = cacheKey(file, maxDimensionPx)
        lruCache.get(key)?.let { return it.imageBitmap }

        return withContext(Dispatchers.IO) {
            lruCache.get(key)?.let { return@withContext it.imageBitmap }

            val decoded = decodeSampledBitmap(file, maxDimensionPx) ?: return@withContext null
            val imageBitmap = decoded.asImageBitmap()
            val kb = (decoded.allocationByteCount / 1024).coerceAtLeast(1)

            decodeMutex.withLock {
                lruCache.put(key, CachedPage(imageBitmap, kb))
            }
            imageBitmap
        }
    }

    fun prefetchAround(scope: CoroutineScope, pages: List<File>, centerIndex: Int, maxDimensionPx: Int = 2048) {
        val indices = listOf(centerIndex, centerIndex + 1, centerIndex - 1, centerIndex + 2)
            .filter { it in pages.indices }
            .distinct()

        for (idx in indices) {
            val file = pages[idx]
            if (getCached(file, maxDimensionPx) == null) {
                scope.launch(Dispatchers.IO) {
                    loadPage(file, maxDimensionPx)
                }
            }
        }
    }

    fun clear() {
        lruCache.evictAll()
    }

    private fun cacheKey(file: File, maxDim: Int): String = "${file.absolutePath}@$maxDim"

    private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) > maxDim || (bounds.outHeight / sampleSize) > maxDim) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }
}
