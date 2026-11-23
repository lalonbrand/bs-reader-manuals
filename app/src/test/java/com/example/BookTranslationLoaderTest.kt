package com.example

import com.example.data.local.BookTranslationLoader
import com.example.data.local.TranslatedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookTranslationLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `saveCachedPageTranslation persists and merges with bundled translations`() {
        val bookDir = tempFolder.newFolder("book_cache_test")
        File(bookDir, BookTranslationLoader.TRANSLATIONS_FILENAME).writeText(
            """
            {
              "version": 1,
              "language": "bn",
              "translator": "bundled",
              "pages": [
                {
                  "page": 1,
                  "paragraphs": [
                    {"en": "Introduction", "bn": "পরিচিতি"}
                  ]
                },
                {
                  "page": 2,
                  "paragraphs": [
                    {"en": "Old text", "bn": "পুরানো লেখা"}
                  ]
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        BookTranslationLoader.saveCachedPageTranslation(
            bookDirPath = bookDir.absolutePath,
            oneBasedPage = 2,
            paragraphs = listOf(TranslatedLine(en = "Updated text", bn = "নতুন বাংলা লেখা"))
        )
        BookTranslationLoader.saveCachedPageTranslation(
            bookDirPath = bookDir.absolutePath,
            oneBasedPage = 5,
            paragraphs = listOf(TranslatedLine(en = "Safety rule", bn = "নিরাপত্তা নিয়ম"))
        )

        assertTrue(File(bookDir, BookTranslationLoader.LOCAL_CACHE_FILENAME).exists())

        val loaded = BookTranslationLoader.loadByOneBasedPage(bookDir.absolutePath)
        assertEquals(setOf(1, 2, 5), loaded.keys)
        assertEquals("পরিচিতি", loaded[1]?.firstOrNull()?.bn)
        assertEquals("নতুন বাংলা লেখা", loaded[2]?.firstOrNull()?.bn)
        assertEquals("নিরাপত্তা নিয়ম", loaded[5]?.firstOrNull()?.bn)
    }
}
