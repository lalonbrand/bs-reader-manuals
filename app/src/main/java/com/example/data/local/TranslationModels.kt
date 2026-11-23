package com.example.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TranslatedLine(
    val en: String,
    val bn: String
)

@Serializable
data class TranslatedPage(
    val page: Int,
    val paragraphs: List<TranslatedLine> = emptyList(),
    val lines: List<TranslatedLine> = emptyList()
) {
    fun resolvedParagraphs(): List<TranslatedLine> =
        if (paragraphs.isNotEmpty()) paragraphs else lines
}

@Serializable
data class BookTranslationBundle(
    val version: Int = 1,
    val language: String = "bn",
    val translator: String = "",
    val pages: List<TranslatedPage> = emptyList()
)

object BookTranslationLoader {
    const val TRANSLATIONS_FILENAME = "translations_bn.json"
    const val LOCAL_CACHE_FILENAME = "translations_bn_cache.json"

    private val cacheFileLock = Any()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun loadByOneBasedPage(bookDirPath: String): Map<Int, List<TranslatedLine>> {
        val merged = LinkedHashMap<Int, List<TranslatedLine>>()
        merged.putAll(readBundleMap(File(bookDirPath, TRANSLATIONS_FILENAME)))
        synchronized(cacheFileLock) {
            merged.putAll(readBundleMap(File(bookDirPath, LOCAL_CACHE_FILENAME)))
        }
        return merged
    }

    fun saveCachedPageTranslation(
        bookDirPath: String,
        oneBasedPage: Int,
        paragraphs: List<TranslatedLine>
    ) {
        if (oneBasedPage < 1) return
        val dir = File(bookDirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        synchronized(cacheFileLock) {
            val cacheFile = File(dir, LOCAL_CACHE_FILENAME)
            val existingMap = readBundleMap(cacheFile).toMutableMap()
            existingMap[oneBasedPage] = paragraphs

            val updatedBundle = BookTranslationBundle(
                version = 1,
                language = "bn",
                translator = "firebase-ai-gemini-3.8-flash",
                pages = existingMap.entries
                    .sortedBy { it.key }
                    .map { (pageNum, list) ->
                        TranslatedPage(page = pageNum, paragraphs = list)
                    }
            )
            val tmpFile = File(dir, "$LOCAL_CACHE_FILENAME.tmp")
            tmpFile.writeText(jsonParser.encodeToString(updatedBundle), Charsets.UTF_8)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.writeText(jsonParser.encodeToString(updatedBundle), Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    private fun readBundleMap(file: File): Map<Int, List<TranslatedLine>> {
        if (!file.exists() || !file.isFile) return emptyMap()
        return runCatching {
            val bundle = jsonParser.decodeFromString<BookTranslationBundle>(file.readText(Charsets.UTF_8))
            bundle.pages.associate { page ->
                val meaningfulBlocks = page.resolvedParagraphs().filter { block ->
                    block.en.any { it.isLetter() } || block.bn.any { it.isLetter() }
                }
                page.page to meaningfulBlocks
            }.filterValues { it.isNotEmpty() }
        }.getOrElse { emptyMap() }
    }
}
