package com.example.data.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "GeminiRestClient"
private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

object GeminiRestClient {

    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private data class ModelAttempt(
        val modelName: String,
        val useLowThinking: Boolean,
        val timeoutMs: Long
    )

    private class GeminiHttpException(
        val statusCode: Int,
        message: String
    ) : IllegalStateException(message)

    suspend fun generateStructuredJson(
        promptText: String,
        images: List<Bitmap> = emptyList(),
        responseSchema: JSONObject,
        temperature: Double = 0.15
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            throw IllegalStateException(
                "Gemini API key is not configured. Add GEMINI_API_KEY in AI Studio Secrets (or GitHub Repository Secrets for Release APK builds) and rebuild."
            )
        }

        val encodedImages = images.map { encodeBitmapAsBase64Jpeg(it) }

        val attempts = listOf(
            ModelAttempt("gemini-3.8-flash", useLowThinking = true, timeoutMs = 25_000L),
            ModelAttempt("gemini-3.5-flash-lite", useLowThinking = false, timeoutMs = 30_000L),
            ModelAttempt("gemini-2.5-flash", useLowThinking = false, timeoutMs = 30_000L)
        )

        var lastError: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            val hasNext = index < attempts.lastIndex
            try {
                val requestBodyJson = buildRequestBodyJson(
                    promptText = promptText,
                    encodedImages = encodedImages,
                    responseSchema = responseSchema,
                    temperature = temperature,
                    useLowThinking = attempt.useLowThinking
                )
                return@withContext withTimeout(attempt.timeoutMs) {
                    executeGenerateContent(
                        modelName = attempt.modelName,
                        apiKey = apiKey,
                        requestBodyJson = requestBodyJson,
                        timeoutMs = attempt.timeoutMs
                    )
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                lastError = e
                Log.w(TAG, "Model ${attempt.modelName} timed out after ${attempt.timeoutMs}ms")
                if (!hasNext) {
                    throw IllegalStateException("AI request timed out. Check your internet connection and retry.", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiHttpException) {
                lastError = e
                val retryable = isRetryableHttpFailure(e.statusCode, e.message.orEmpty())
                Log.w(TAG, "Model ${attempt.modelName} failed HTTP ${e.statusCode} (retryable=$retryable): ${e.message}")
                if (!retryable || !hasNext) {
                    throw e
                }
                delay(450L)
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Network error on ${attempt.modelName}: ${e.message}")
                if (!hasNext) {
                    throw IllegalStateException("Network error calling Gemini API: ${e.localizedMessage ?: "Check internet connection."}", e)
                }
                delay(450L)
            }
        }
        throw lastError ?: IllegalStateException("Gemini API request failed.")
    }

    private fun buildRequestBodyJson(
        promptText: String,
        encodedImages: List<String>,
        responseSchema: JSONObject,
        temperature: Double,
        useLowThinking: Boolean
    ): String {
        val parts = JSONArray()
        for (b64 in encodedImages) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", b64)
                )
            )
        }
        parts.put(JSONObject().put("text", promptText))

        val generationConfig = JSONObject()
            .put("temperature", temperature)
            .put("responseMimeType", "application/json")
            .put("responseSchema", responseSchema)

        if (useLowThinking) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", "LOW")
            )
        }

        val root = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put("generationConfig", generationConfig)

        return root.toString()
    }

    private suspend fun executeGenerateContent(
        modelName: String,
        apiKey: String,
        requestBodyJson: String,
        timeoutMs: Long
    ): String {
        val client = baseHttpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/$modelName:generateContent")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val responseBody = client.newCall(request).awaitString()
        return extractResponseText(responseBody)
    }

    private suspend fun Call.awaitString(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        val parsedErr = parseApiErrorMessage(res.code, raw)
                        if (cont.isActive) {
                            cont.resumeWithException(GeminiHttpException(res.code, parsedErr))
                        }
                        return
                    }
                    if (cont.isActive) {
                        cont.resume(raw)
                    }
                }
            }
        })
    }

    private fun extractResponseText(rawResponseJson: String): String {
        val root = JSONObject(rawResponseJson)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini API returned no candidates.")
        if (candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            val detail = if (blockReason.isNotBlank()) " ($blockReason)" else ""
            throw IllegalStateException("Gemini API returned an empty response$detail.")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val contentObj = firstCandidate.optJSONObject("content")
            ?: throw IllegalStateException("Gemini response is missing content.")
        val parts = contentObj.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini response is missing parts.")

        val textBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text")
            if (text.isNotEmpty()) {
                textBuilder.append(text)
            }
        }
        val finalText = textBuilder.toString().trim()
        if (finalText.isEmpty()) {
            throw IllegalStateException("Gemini returned empty text.")
        }
        return finalText
    }

    private fun parseApiErrorMessage(statusCode: Int, rawBody: String): String {
        val parsed = runCatching {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            errObj?.optString("message")?.trim().orEmpty()
        }.getOrDefault("")

        if (parsed.contains("API key not valid", ignoreCase = true) ||
            parsed.contains("API_KEY_INVALID", ignoreCase = true)
        ) {
            return "Invalid GEMINI_API_KEY. Please check your key in AI Studio Secrets or GitHub Repository Secrets."
        }
        return parsed.ifBlank { "Gemini API error (HTTP $statusCode)." }
    }

    private fun isRetryableHttpFailure(statusCode: Int, message: String): Boolean {
        if (statusCode == 404 || statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return true
        }
        val lower = message.lowercase()
        return lower.contains("high demand") ||
            lower.contains("overloaded") ||
            lower.contains("rate limit") ||
            lower.contains("resource exhausted") ||
            lower.contains("not found") ||
            lower.contains("try again")
    }

    private fun encodeBitmapAsBase64Jpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
