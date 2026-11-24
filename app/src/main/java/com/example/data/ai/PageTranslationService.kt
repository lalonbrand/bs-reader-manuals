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
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import kotlin.math.max

private const val TAG = "PageTranslationService"
private const val MAX_IMAGE_SIDE_PX = 1280
private const val PRIMARY_ATTEMPT_TIMEOUT_MS = 25_000L
private const val FALLBACK_ATTEMPT_TIMEOUT_MS = 32_000L
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

    private val primaryTranslationModel by lazy {
        createTranslationModel("gemini-3.8-flash", lowThinking = true)
    }

    private val fallbackTranslationModel by lazy {
        createTranslationModel("gemini-3.5-flash-lite", lowThinking = false)
    }

    private fun createTranslationModel(modelName: String, lowThinking: Boolean) =
        Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationSchema
                temperature = 0.15f
                if (lowThinking) {
                    thinkingConfig = thinkingConfig {
                        thinkingLevel = ThinkingLevel.LOW
                    }
                }
            }
        )

    suspend fun translatePageImage(pageFile: File): List<TranslatedLine> = withContext(Dispatchers.IO) {
        if (!pageFile.exists() || !pageFile.isFile) {
            error("Page image file not found on device.")
        }

        val bitmap = decodeScaledBitmap(pageFile)
            ?: error("Unable to decode page image for translation.")

        try {
            val response = try {
                withTimeout(TRANSLATION_TIMEOUT_MS) {
                    generateWithFallback(bitmap, pageFile.name)
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

    private suspend fun generateWithFallback(
        bitmap: Bitmap,
        fileName: String
    ): com.google.firebase.ai.type.GenerateContentResponse {
        val attempts = listOf(
            Triple("gemini-3.8-flash", primaryTranslationModel, PRIMARY_ATTEMPT_TIMEOUT_MS),
            Triple("gemini-3.5-flash-lite", fallbackTranslationModel, FALLBACK_ATTEMPT_TIMEOUT_MS)
        )
        var lastError: Exception? = null
        for ((index, entry) in attempts.withIndex()) {
            val (modelName, model, attemptTimeoutMs) = entry
            val hasNext = index < attempts.lastIndex
            try {
                return withTimeout(attemptTimeoutMs) {
                    model.generateContent(
                        content {
                            image(bitmap)
                            text(systemPromptText)
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                lastError = e
                Log.w(TAG, "Model $modelName timed out after ${attemptTimeoutMs}ms for $fileName")
                if (!hasNext) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val isTransient = isTransientDemandError(e)
                Log.w(TAG, "Model $modelName failed for $fileName (transient=$isTransient): ${e.message}")
                if (!isTransient || !hasNext) {
                    throw e
                }
                kotlinx.coroutines.delay(600L)
            }
        }
        throw lastError ?: IllegalStateException("Translation failed.")
    }

    private fun isTransientDemandError(error: Throwable): Boolean {
        val fullMessage = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()
        return fullMessage.contains("high demand") ||
            fullMessage.contains("overloaded") ||
            fullMessage.contains("unavailable") ||
            fullMessage.contains("503") ||
            fullMessage.contains("429") ||
            fullMessage.contains("resource exhausted") ||
            fullMessage.contains("rate limit") ||
            fullMessage.contains("too many requests") ||
            fullMessage.contains("try again")
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
