package com.example.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.example.data.local.TranslatedLine
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import kotlin.math.max

private const val TAG = "PageTranslationService"
private const val MAX_IMAGE_SIDE_PX = 1600
private const val TRANSLATION_TIMEOUT_MS = 60_000L

class PageTranslationService {

    private val translationSchema = Schema.obj(
        properties = mapOf(
            "paragraphs" to Schema.array(
                description = "All headings, paragraphs, notes, and list items in top-to-bottom reading order",
                items = Schema.obj(
                    properties = mapOf(
                        "en" to Schema.string(
                            description = "Exact English heading, paragraph, or list item text from the page"
                        ),
                        "bn" to Schema.string(
                            description = "Strictly faithful natural Bangladeshi Bangla translation without any added explanation"
                        )
                    )
                )
            )
        )
    )

    private val systemPromptText = """
        You are an experienced Bangladeshi technical translator for garment manufacturing and quality control manuals.
        Inspect the attached manual page image carefully and extract every readable heading, paragraph, table row, warning, and bullet item in natural top-to-bottom reading order.

        CRITICAL PARAGRAPH & FAITHFULNESS RULES:
        1. Group body prose into COMPLETE paragraphs and headings — do NOT split a single paragraph into individual visual lines.
        2. Translate strictly what is written on the page into natural, conversational Bangladeshi Bangla (বাংলাদেশের প্রচলিত বাংলা).
        3. NO EXTRA EXPLANATIONS: Never add extra explanations, examples, parenthetical clarifications, background context, or commentary that are not printed on the page.
        4. NO OMISSIONS: Keep all numbers, measurements, percentages, dates, section numbers, and technical values exact.
        5. Keep standard garment industry abbreviations intact where natural: QC, QA, AQL, SPI, GSM, PPE, SOP, RPM.
        6. Use clear garment-industry Bangla terms naturally (e.g., অপারেটর, সুপারভাইজার, ফেব্রিক/কাপড়, সুই/নিডল, ত্রুটি/ডিফেক্ট, কাজের নির্দেশনা, নিরাপত্তামূলক সতর্কতা).
        7. If the page is blank, purely decorative artwork, or contains no readable English sentences/words, return {"paragraphs": []}.
    """.trimIndent()

    private val translationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationSchema
                temperature = 0.15f
            }
        )
    }

    suspend fun translatePageImage(pageFile: File): List<TranslatedLine> = withContext(Dispatchers.IO) {
        if (!pageFile.exists() || !pageFile.isFile) {
            error("Page image file not found on device.")
        }

        val bitmap = decodeScaledBitmap(pageFile)
            ?: error("Unable to decode page image for translation.")

        try {
            val response = try {
                withTimeout(TRANSLATION_TIMEOUT_MS) {
                    translationModel.generateContent(
                        content {
                            image(bitmap)
                            text(systemPromptText)
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Page translation timed out for ${pageFile.name}", e)
                throw IllegalStateException("Translation timed out after 60 seconds. Check internet connection and retry.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI page translation failed for ${pageFile.name}", e)
                throw IllegalStateException(
                    e.localizedMessage ?: "Failed to translate this page. Please check internet connection and retry.",
                    e
                )
            }

            val rawJson = response.text
                ?: throw IllegalStateException("AI returned an empty translation response.")

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
            val en = obj.optString("en").trim()
            val bn = obj.optString("bn").trim()
            if (bn.any { it.isLetter() } || en.any { it.isLetter() }) {
                if (bn.isNotEmpty()) {
                    result.add(TranslatedLine(en = en.ifBlank { bn }, bn = bn))
                }
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
