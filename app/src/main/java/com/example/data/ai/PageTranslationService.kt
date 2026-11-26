package com.example.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.example.data.local.TranslatedLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

private const val TAG = "PageTranslationService"
private const val MAX_IMAGE_SIDE_PX = 1280
private const val TRANSLATION_TIMEOUT_MS = 70_000L

class PageTranslationService {

    private val translationSchemaJson: JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put(
            "properties",
            JSONObject().put(
                "paragraphs",
                JSONObject().apply {
                    put("type", "ARRAY")
                    put("description", "Bangla paragraphs in top-to-bottom reading order without copying full English paragraphs")
                    put(
                        "items",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "heading",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put("description", "Short English section number/heading only if present (max 8 words), otherwise empty string")
                                    )
                                    put(
                                        "bn",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put("description", "Complete natural Bangladeshi Bangla translation of this heading, paragraph, table row, or bullet item")
                                    )
                                }
                            )
                            put("required", JSONArray().put("bn"))
                        }
                    )
                }
            )
        )
        put("required", JSONArray().put("paragraphs"))
    }

    private val systemPromptText = """
        You are an experienced Bangladeshi technical translator for garment manufacturing and quality control manuals.
        Inspect the attached manual page image carefully and translate every readable heading, paragraph, table row, warning, and bullet item in natural top-to-bottom reading order.

        SPEED & FAITHFULNESS RULES:
        1. Output natural, conversational Bangladeshi Bangla ("bn") for complete paragraphs—do NOT split one paragraph into line-by-line fragments, and do NOT re-type full English body paragraphs (put only a short English section label/heading in "heading" when helpful, or "" for body text).
        2. Translate strictly what is written on the page. Never add extra explanations, parenthetical clarifications, examples, or commentary not printed on the page.
        3. Keep all numbers, measurements, percentages, dates, section numbers, and technical specifications exact.
        4. Keep standard garment abbreviations natural where appropriate: QC, QA, AQL, SPI, GSM, PPE, SOP, RPM.
        5. Use clear garment-industry Bangla terms (e.g., অপারেটর, সুপারভাইজার, ফেব্রিক/কাপড়, সুই/নিডল, ত্রুটি/ডিফেক্ট, কাজের নির্দেশনা, নিরাপত্তামূলক সতর্কতা).
        6. If the page is blank, purely decorative artwork, or has no readable English text, return {"paragraphs": []}.
    """.trimIndent()

    suspend fun translatePageImage(pageFile: File): List<TranslatedLine> = withContext(Dispatchers.IO) {
        if (!pageFile.exists() || !pageFile.isFile) {
            error("Page image file not found on device.")
        }

        val bitmap = decodeScaledBitmap(pageFile)
            ?: error("Unable to decode page image for translation.")

        try {
            val rawJson = try {
                withTimeout(TRANSLATION_TIMEOUT_MS) {
                    GeminiRestClient.generateStructuredJson(
                        promptText = systemPromptText,
                        images = listOf(bitmap),
                        responseSchema = translationSchemaJson,
                        temperature = 0.15
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Page translation timed out for ${pageFile.name}", e)
                throw IllegalStateException("Translation timed out. Check internet connection and retry.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini page translation failed for ${pageFile.name}", e)
                throw IllegalStateException(
                    e.localizedMessage ?: "Failed to translate this page. Please check internet connection and retry.",
                    e
                )
            }

            parseParagraphsJson(rawJson)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun parseParagraphsJson(rawJson: String): List<TranslatedLine> {
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("paragraphs") ?: return emptyList()
        val result = mutableListOf<TranslatedLine>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val headingOrEn = obj.optString("heading").ifBlank { obj.optString("en") }.trim()
            val bn = obj.optString("bn").trim()
            if (bn.isNotEmpty() && (bn.any { it.isLetter() } || headingOrEn.any { it.isLetter() })) {
                result.add(TranslatedLine(en = headingOrEn, bn = bn))
            }
        }
        return result
    }

    private fun decodeScaledBitmap(file: File): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        val longest = max(srcW, srcH)
        while ((longest / sampleSize) > MAX_IMAGE_SIDE_PX * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

        val decLongest = max(decoded.width, decoded.height)
        val targetBitmap = if (decLongest > MAX_IMAGE_SIDE_PX) {
            val scale = MAX_IMAGE_SIDE_PX.toFloat() / decLongest.toFloat()
            val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled != decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        // Composite onto white background to ensure clean contrast even if WebP has alpha
        if (!targetBitmap.hasAlpha()) return targetBitmap
        val opaque = Bitmap.createBitmap(targetBitmap.width, targetBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(targetBitmap, 0f, 0f, null)
        targetBitmap.recycle()
        return opaque
    }
}
