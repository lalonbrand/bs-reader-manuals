package com.example

import com.example.data.remote.NetworkModule
import com.example.data.storage.ZipBookExtractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipBookExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `extractFromStream sorts pages naturally and filters macOS junk`() = runTest {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            listOf(
                "my_book/page_10.webp",
                "my_book/page_2.webp",
                "my_book/page_1.webp",
                "__MACOSX/my_book/._page_1.webp",
                "my_book/cover.webp"
            ).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3, 4))
                zos.closeEntry()
            }
        }

        val targetDir = File(tempFolder.root, "book_out")
        val result = ZipBookExtractor.extractFromStream(
            ByteArrayInputStream(baos.toByteArray()),
            targetDir
        )

        assertEquals(3, result.pageCount)
        assertTrue(File(targetDir, "cover.webp").exists())
        assertTrue(File(targetDir, "page_001.webp").exists())
        assertTrue(File(targetDir, "page_002.webp").exists())
        assertTrue(File(targetDir, "page_003.webp").exists())
    }

    @Test(expected = SecurityException::class)
    fun `extractFromStream rejects zip slip traversal`() = runTest {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.webp"))
            zos.write(byteArrayOf(9, 9))
            zos.closeEntry()
        }

        val targetDir = File(tempFolder.root, "safe_dir")
        ZipBookExtractor.extractFromStream(
            ByteArrayInputStream(baos.toByteArray()),
            targetDir
        )
    }

    @Test
    fun `parseCatalogJson supports object wrapper and bare array`() {
        val wrapped = """
            {
              "catalogTitle": "Editorial Press",
              "books": [
                {
                  "id": "b1",
                  "title": "Folio One",
                  "author": "A. Writer",
                  "zipUrl": "https://example.com/b1.zip",
                  "pageCount": 12
                }
              ]
            }
        """.trimIndent()
        val parsedWrapped = NetworkModule.parseCatalogJson(wrapped)
        assertEquals(1, parsedWrapped.books.size)
        assertEquals("Folio One", parsedWrapped.books.first().title)

        val bareArray = """
            [
              {
                "id": "b2",
                "title": "Folio Two",
                "zipUrl": "https://example.com/b2.zip"
              }
            ]
        """.trimIndent()
        val parsedArray = NetworkModule.parseCatalogJson(bareArray)
        assertEquals(1, parsedArray.books.size)
        assertEquals("Folio Two", parsedArray.books.first().title)
    }
}
