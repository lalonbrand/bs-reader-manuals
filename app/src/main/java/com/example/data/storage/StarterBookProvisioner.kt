package com.example.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

object StarterBookProvisioner {
    const val STARTER_BOOK_ID = "celestial-almanac-01"

    suspend fun ensureStarterBookExtracted(context: Context): ExtractionResult =
        withContext(Dispatchers.IO) {
            val targetDir = File(context.filesDir, "books/$STARTER_BOOK_ID")
            val existingPages = targetDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("page_") && it.extension == "webp" }
                ?.sortedWith(NaturalFilenameComparator)
                ?: emptyList()

            if (existingPages.size >= 8) {
                val totalBytes = existingPages.sumOf { it.length() }
                return@withContext ExtractionResult(
                    bookDirectory = targetDir,
                    pageCount = existingPages.size,
                    coverFilePath = existingPages.first().absolutePath,
                    totalExtractedBytes = totalBytes
                )
            }

            val tempZip = File(context.cacheDir, "starter_celestial_almanac.zip")
            generateStarterZip(context, tempZip)

            val result = FileInputStream(tempZip).use { fis ->
                ZipBookExtractor.extractFromStream(fis, targetDir)
            }
            tempZip.delete()
            result
        }

    private fun generateStarterZip(context: Context, outZip: File) {
        val serifFace = ResourcesCompat.getFont(context, R.font.cormorant_garamond) ?: Typeface.SERIF
        val sansFace = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) ?: Typeface.SANS_SERIF
        val monoFace = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE

        val pageWidth = 1080
        val pageHeight = 1520

        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            for (pageNum in 1..8) {
                val bmp = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)

                when (pageNum) {
                    1 -> drawCoverPage(context, canvas, pageWidth, pageHeight, serifFace, monoFace)
                    2 -> drawColophonPage(canvas, pageWidth, pageHeight, serifFace, sansFace, monoFace)
                    3 -> drawEssayPage(
                        canvas = canvas,
                        w = pageWidth,
                        h = pageHeight,
                        serif = serifFace,
                        sans = sansFace,
                        mono = monoFace,
                        chapterLabel = "CHAPTER I",
                        title = "Of Armillary Spheres & Celestial Order",
                        dropCap = "W",
                        paragraphs = listOf(
                            "hen brass rings are nested in meridian alignment, the observer holds a miniature firmament between thumb and forefinger. Each engraved degree marks both the passing of hours and the patience of the cartographer.",
                            "In this folio specimen, every sheet is pre-rasterized into a crisp WebP plate rather than parsed on the fly from heavy vector streams. Turning a page becomes pure optics and geometry—light across vellum, shadow beneath the fold, and zero decoding stutter.",
                            "Drag gently from the right margin, or lift the lower corner diagonally toward the spine, to feel the paper bend around its virtual cylinder."
                        ),
                        pageFooter = "03"
                    )
                    4 -> drawPlatePage(
                        context = context,
                        canvas = canvas,
                        w = pageWidth,
                        h = pageHeight,
                        serif = serifFace,
                        mono = monoFace,
                        drawableId = R.drawable.atlas_plate_art_1794718405568,
                        plateTitle = "PLATE IV — BOTANICAL & ECLIPSE STUDY",
                        caption = "Polypodium specimens juxtaposed with solar totality geometry and polyhedral projections."
                    )
                    5 -> drawEssayPage(
                        canvas = canvas,
                        w = pageWidth,
                        h = pageHeight,
                        serif = serifFace,
                        sans = sansFace,
                        mono = monoFace,
                        chapterLabel = "CHAPTER II",
                        title = "Why Pre-Rasterized WebP Folios Fly",
                        dropCap = "T",
                        paragraphs = listOf(
                            "raditional PDF parsers allocate massive glyph caches and tessellate thousands of Bezier paths per frame, stalling mobile GPUs during rapid interactive page curls.",
                            "By compiling complex typography, marginalia, and copperplate engravings ahead of time into compact lossy or lossless WebP archives (.zip), BS Reader keeps memory overhead predictable via an LRU page cache.",
                            "Adjacent pages (n - 1, n, n + 1) are decoded asynchronously on background IO threads so your finger tracks the curling sheet at the full refresh rate of the display."
                        ),
                        pageFooter = "05"
                    )
                    6 -> drawDiagramPage(canvas, pageWidth, pageHeight, serifFace, monoFace)
                    7 -> drawEssayPage(
                        canvas = canvas,
                        w = pageWidth,
                        h = pageHeight,
                        serif = serifFace,
                        sans = sansFace,
                        mono = monoFace,
                        chapterLabel = "CHAPTER III",
                        title = "Remote Catalogs & Archive Portability",
                        dropCap = "E",
                        paragraphs = listOf(
                            "very volume in your bookshelf can arrive either from a lightweight remote catalog.json index or directly from local storage as a standard .zip bundle of page_001.webp, page_002.webp plates.",
                            "Archives are extracted with strict canonical path verification into private application storage, preserving reading position and surface preferences across sessions.",
                            "Swipe from the left margin at any time to curl back through earlier plates, or tap the center of any sheet to reveal the reading scrubber and page grid."
                        ),
                        pageFooter = "07"
                    )
                    8 -> drawEndpaperPage(canvas, pageWidth, pageHeight, serifFace, monoFace)
                }

                val entryName = "page_%03d.webp".format(pageNum)
                zos.putNextEntry(ZipEntry(entryName))
                val baos = ByteArrayOutputStream()
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                bmp.compress(format, 88, baos)
                zos.write(baos.toByteArray())
                zos.closeEntry()
                bmp.recycle()
            }
        }
    }

    private fun drawVellumBase(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Color.parseColor("#FAF4E6"))
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D9CCB5")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(56f, 56f, w - 56f, h - 56f, borderPaint)
        borderPaint.strokeWidth = 1.5f
        canvas.drawRect(68f, 68f, w - 68f, h - 68f, borderPaint)
    }

    private fun drawCoverPage(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        mono: Typeface
    ) {
        canvas.drawColor(Color.parseColor("#182032"))
        val art = BitmapFactory.decodeResource(context.resources, R.drawable.atlas_cover_art_1794718404905)
        if (art != null) {
            val dest = Rect(0, 0, w, h)
            canvas.drawBitmap(art, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            art.recycle()
        }

        val scrim = Paint().apply {
            color = Color.argb(170, 18, 22, 34)
        }
        canvas.drawRect(88f, h * 0.67f, w - 88f, h - 96f, scrim)

        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DFB86C")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(98f, h * 0.67f + 10f, w - 98f, h - 106f, frame)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FAF3E0")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 68f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("THE CELESTIAL", w / 2f, h * 0.755f, titlePaint)
        canvas.drawText("ALMANAC", w / 2f, h * 0.815f, titlePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E3C27A")
            typeface = mono
            textSize = 26f
            letterSpacing = 0.14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("FOLIO SPECIMEN • 8 WEBP PLATES", w / 2f, h * 0.875f, subPaint)
    }

    private fun drawColophonPage(
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        sans: Typeface,
        mono: Typeface
    ) {
        drawVellumBase(canvas, w, h)
        val centerX = w / 2f

        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            typeface = mono
            textSize = 24f
            letterSpacing = 0.18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("FOLIOCURL ARCHIVAL PRESS", centerX, 310f, monoPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 76f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("The Celestial", centerX, 500f, titlePaint)
        canvas.drawText("& Botanical", centerX, 588f, titlePaint)
        canvas.drawText("Almanac", centerX, 676f, titlePaint)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B8862D")
            strokeWidth = 3f
        }
        canvas.drawLine(centerX - 110f, 745f, centerX + 110f, 745f, dividerPaint)

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A423A")
            typeface = sans
            textSize = 31f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("A pre-rasterized WebP demonstration codex", centerX, 830f, bodyPaint)
        canvas.drawText("crafted to showcase tactile page-curl optics.", centerX, 878f, bodyPaint)

        monoPaint.color = Color.parseColor("#6E6358")
        monoPaint.textSize = 22f
        canvas.drawText("FORMAT: WEBP LOSSY • 1080 × 1520 PX", centerX, 1280f, monoPaint)
        canvas.drawText("ARCHIVE: PAGE_001.WEBP — PAGE_008.WEBP", centerX, 1320f, monoPaint)
    }

    private fun drawEssayPage(
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        sans: Typeface,
        mono: Typeface,
        chapterLabel: String,
        title: String,
        dropCap: String,
        paragraphs: List<String>,
        pageFooter: String
    ) {
        drawVellumBase(canvas, w, h)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            typeface = mono
            textSize = 23f
            letterSpacing = 0.2f
        }
        canvas.drawText(chapterLabel, 130f, 165f, headerPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 62f
        }
        val titleLayout = StaticLayout.Builder
            .obtain(title, 0, title.length, titlePaint, w - 260)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.05f)
            .build()
        canvas.save()
        canvas.translate(130f, 200f)
        titleLayout.draw(canvas)
        canvas.restore()

        val ruleY = 220f + titleLayout.height + 26f
        val rulePaint = Paint().apply {
            color = Color.parseColor("#C5933A")
            strokeWidth = 3f
        }
        canvas.drawLine(130f, ruleY, 270f, ruleY, rulePaint)

        // Drop cap + paragraphs
        val dropPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 112f
        }
        var currentY = ruleY + 64f
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#292420")
            typeface = sans
            textSize = 33f
        }

        paragraphs.forEachIndexed { idx, para ->
            if (idx == 0) {
                canvas.drawText(dropCap, 130f, currentY + 82f, dropPaint)
                val firstLayout = StaticLayout.Builder
                    .obtain(para, 0, para.length, bodyPaint, w - 340)
                    .setLineSpacing(10f, 1.24f)
                    .build()
                canvas.save()
                canvas.translate(212f, currentY)
                firstLayout.draw(canvas)
                canvas.restore()
                currentY += firstLayout.height + 44f
            } else {
                val layout = StaticLayout.Builder
                    .obtain(para, 0, para.length, bodyPaint, w - 260)
                    .setLineSpacing(10f, 1.24f)
                    .build()
                canvas.save()
                canvas.translate(130f, currentY)
                layout.draw(canvas)
                canvas.restore()
                currentY += layout.height + 42f
            }
        }

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#786D61")
            typeface = mono
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— $pageFooter —", w / 2f, h - 108f, footerPaint)
    }

    private fun drawPlatePage(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        mono: Typeface,
        drawableId: Int,
        plateTitle: String,
        caption: String
    ) {
        drawVellumBase(canvas, w, h)
        val art = BitmapFactory.decodeResource(context.resources, drawableId)
        val plateRect = RectF(118f, 130f, w - 118f, h - 290f)
        if (art != null) {
            canvas.drawBitmap(art, null, plateRect, Paint(Paint.FILTER_BITMAP_FLAG))
            art.recycle()
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            typeface = mono
            textSize = 23f
            letterSpacing = 0.14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(plateTitle, w / 2f, h - 220f, titlePaint)

        val capPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3E3731")
            typeface = serif
            textSize = 31f
        }
        val capLayout = StaticLayout.Builder
            .obtain(caption, 0, caption.length, capPaint, w - 240)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(120f, h - 196f)
        capLayout.draw(canvas)
        canvas.restore()
    }

    private fun drawDiagramPage(
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        mono: Typeface
    ) {
        drawVellumBase(canvas, w, h)
        val cx = w / 2f
        val cy = h * 0.44f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 52f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Astrolabe Projection & Meridian Grid", cx, 185f, titlePaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2E3A52")
            strokeWidth = 3.5f
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#B8862D")
            strokeWidth = 2.5f
        }

        listOf(340f, 300f, 240f, 175f, 95f).forEachIndexed { i, r ->
            canvas.drawCircle(cx, cy, r, if (i % 2 == 0) ringPaint else goldPaint)
        }

        for (deg in 0 until 360 step 15) {
            val rad = Math.toRadians(deg.toDouble())
            val rInner = if (deg % 45 == 0) 95f else 280f
            val rOuter = 340f
            canvas.drawLine(
                (cx + rInner * cos(rad)).toFloat(),
                (cy + rInner * sin(rad)).toFloat(),
                (cx + rOuter * cos(rad)).toFloat(),
                (cy + rOuter * sin(rad)).toFloat(),
                if (deg % 90 == 0) ringPaint else goldPaint
            )
        }

        val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5C534A")
            typeface = mono
            textSize = 23f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("FIG. II — AZIMUTHAL RETICLE • 15° INTERVALS", cx, h - 250f, notePaint)
        canvas.drawText("— 06 —", cx, h - 108f, notePaint)
    }

    private fun drawEndpaperPage(
        canvas: Canvas,
        w: Int,
        h: Int,
        serif: Typeface,
        mono: Typeface
    ) {
        drawVellumBase(canvas, w, h)
        val cx = w / 2f

        val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, h * 0.42f, 92f, sealPaint)
        sealPaint.strokeWidth = 2f
        canvas.drawCircle(cx, h * 0.42f, 76f, sealPaint)

        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9F4722")
            typeface = mono
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("FINIS", cx, h * 0.42f + 10f, monoPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            typeface = Typeface.create(serif, Typeface.BOLD)
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("End of Specimen Codex", cx, h * 0.57f, titlePaint)

        monoPaint.color = Color.parseColor("#6E6358")
        monoPaint.textSize = 23f
        canvas.drawText("USE CATALOG SYNC OR IMPORT .ZIP TO ADD BOOKS", cx, h * 0.63f, monoPaint)
    }
}
