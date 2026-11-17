package com.example.data.local

import kotlinx.serialization.Serializable
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
    val lines: List<TranslatedLine> = emptyList()
)

@Serializable
data class BookTranslationBundle(
    val version: Int = 1,
    val language: String = "bn",
    val translator: String = "",
    val pages: List<TranslatedPage> = emptyList()
)

object BookTranslationLoader {
    const val TRANSLATIONS_FILENAME = "translations_bn.json"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadByOneBasedPage(bookDirPath: String): Map<Int, List<TranslatedLine>> {
        val file = File(bookDirPath, TRANSLATIONS_FILENAME)
        if (!file.exists() || !file.isFile) return emptyMap()
        return runCatching {
            val bundle = jsonParser.decodeFromString<BookTranslationBundle>(file.readText(Charsets.UTF_8))
            bundle.pages.associate { it.page to it.lines }
        }.getOrElse { emptyMap() }
    }
}
