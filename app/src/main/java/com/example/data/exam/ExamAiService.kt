package com.example.data.exam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.round

private const val TAG = "ExamAiService"
private const val PAGES_PER_BATCH = 3
private const val MAX_RENDER_SIDE_PX = 1400

data class ExamGradingSummary(
    val obtainedMarks: Double,
    val totalMarks: Int,
    val percentage: Double,
    val mcqCorrect: Int,
    val mcqTotal: Int,
    val fillMarks: Double,
    val fillTotalMarks: Int,
    val descMarks: Double,
    val descTotalMarks: Int,
    val items: List<GradedAnswerItem>
)

class ExamAiService {

    private val extractionSchema = Schema.obj(
        properties = mapOf(
            "questions" to Schema.array(
                description = "All extracted exam questions and answers from these PDF pages",
                items = Schema.obj(
                    properties = mapOf(
                        "questionType" to Schema.enumeration(
                            values = listOf("MCQ", "FILL_IN_BLANK", "DESCRIPTIVE"),
                            description = "MCQ for multiple choice, FILL_IN_BLANK for fill-in-the-blanks, DESCRIPTIVE for written descriptive answers"
                        ),
                        "prompt" to Schema.string(
                            description = "Clean question statement. For FILL_IN_BLANK use ____ where the blank appears."
                        ),
                        "options" to Schema.array(
                            items = Schema.string(),
                            description = "Option choices for MCQ without A/B/C prefix unless helpful; empty array for FILL_IN_BLANK and DESCRIPTIVE"
                        ),
                        "correctOptionIndex" to Schema.integer(
                            description = "0-based index of the correct MCQ option, or -1 if not MCQ"
                        ),
                        "correctAnswer" to Schema.string(
                            description = "Exact answer or complete reference descriptive answer from the Q&A PDF"
                        ),
                        "marks" to Schema.integer(
                            description = "Marks allocated in the PDF, or 1 for MCQ/FILL_IN_BLANK and 5 for DESCRIPTIVE when unspecified"
                        )
                    )
                )
            )
        )
    )

    private val gradingSchema = Schema.obj(
        properties = mapOf(
            "grades" to Schema.array(
                description = "Evaluations for each graded candidate answer",
                items = Schema.obj(
                    properties = mapOf(
                        "questionId" to Schema.string(description = "Matching questionId"),
                        "awardedMarks" to Schema.double(description = "Awarded marks between 0.0 and maxMarks"),
                        "feedback" to Schema.string(description = "Concise 1-2 sentence grading feedback explaining correctness or missing points")
                    )
                )
            )
        )
    )

    private val primaryExtractorModel by lazy {
        createExtractorModel("gemini-3.8-flash")
    }

    private val fallbackExtractorModel by lazy {
        createExtractorModel("gemini-3.5-flash-lite")
    }

    private val primaryGraderModel by lazy {
        createGraderModel("gemini-3.8-flash")
    }

    private val fallbackGraderModel by lazy {
        createGraderModel("gemini-3.5-flash-lite")
    }

    private fun createExtractorModel(modelName: String) =
        Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = extractionSchema
                temperature = 0.1f
            }
        )

    private fun createGraderModel(modelName: String) =
        Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = gradingSchema
                temperature = 0.1f
            }
        )

    suspend fun parseQaPdfUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): ParsedPdfResult = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(context, uri)
        val suggestedTitle = displayName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Uploaded Q&A Set" }

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open the selected PDF file.")

        val extractedDrafts = mutableListOf<DraftExamQuestion>()
        val seenPrompts = HashSet<String>()
        var totalPageCount = 0

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                totalPageCount = renderer.pageCount
                if (totalPageCount <= 0) {
                    error("The selected PDF has no readable pages.")
                }

                var startPage = 0
                while (startPage < totalPageCount) {
                    val endPage = minOf(startPage + PAGES_PER_BATCH, totalPageCount)
                    onProgress("Scanning PDF pages ${startPage + 1}–$endPage of $totalPageCount...")

                    val bitmaps = mutableListOf<Bitmap>()
                    try {
                        for (pageIdx in startPage until endPage) {
                            renderer.openPage(pageIdx).use { page ->
                                bitmaps.add(renderPageBitmap(page))
                            }
                        }

                        val chunkPrompt = """
                            You are inspecting pages ${startPage + 1} to $endPage (out of $totalPageCount) of an Exam Question & Answer PDF.
                            Extract EVERY complete question and its answer shown on these pages into the JSON schema.

                            Classification rules:
                            - "MCQ": Multiple-choice questions with options. Set "options" (2 to 6 items), "correctOptionIndex" (0-based), and "correctAnswer" to the correct option text.
                            - "FILL_IN_BLANK": Fill-in-the-blank questions (even if shown with ____, dots, or brackets). Preserve "____" in "prompt", empty "options", "correctOptionIndex" = -1, and put the expected fill word/phrase in "correctAnswer".
                            - "DESCRIPTIVE": Short-answer, written, or descriptive explanation questions. Empty "options", "correctOptionIndex" = -1, and copy the full reference answer/key explanation from the PDF into "correctAnswer".

                            Marks rule:
                            - Use the marks printed in the PDF for each question if shown.
                            - If marks are not printed: use 1 for MCQ, 1 for FILL_IN_BLANK, and 5 for DESCRIPTIVE.

                            Strictness:
                            - Do NOT invent questions not present on these pages.
                            - Preserve original wording (English or Bangla) accurately.
                            - If a question has an answer key later on the same pages, match them together.
                        """.trimIndent()

                        val response = withTimeout(60_000L) {
                            generateExtractorWithFallback(bitmaps, chunkPrompt)
                        }

                        val rawJson = response.text
                            ?: throw IllegalStateException("AI returned an empty response while scanning pages ${startPage + 1}–$endPage.")
                        val parsedChunk = parseExtractionJson(rawJson)
                        for (item in parsedChunk) {
                            val normKey = item.prompt.lowercase().replace(Regex("\\s+"), " ").trim()
                            if (normKey.isNotEmpty() && seenPrompts.add(normKey)) {
                                extractedDrafts.add(item)
                            }
                        }
                    } finally {
                        bitmaps.forEach { it.recycle() }
                    }

                    startPage = endPage
                }
            }
        }

        if (extractedDrafts.isEmpty()) {
            error("No questions and answers could be extracted from \"$displayName\". Make sure the PDF contains readable Q&A content.")
        }

        ParsedPdfResult(
            fileName = displayName,
            suggestedTitle = suggestedTitle,
            pageCount = totalPageCount,
            questions = extractedDrafts.take(300)
        )
    }

    suspend fun gradeExamAnswers(
        questions: List<ExamQuestion>,
        mcqSelections: Map<String, Int>,
        textAnswers: Map<String, String>
    ): ExamGradingSummary = withContext(Dispatchers.Default) {
        val aiQueue = mutableListOf<ExamQuestion>()
        val preGraded = LinkedHashMap<String, GradedAnswerItem>()

        for (question in questions) {
            when (question.parsedType) {
                QuestionType.MCQ -> {
                    val selectedIdx = mcqSelections[question.id] ?: -1
                    val userAnswerText = if (selectedIdx in question.options.indices) {
                        question.options[selectedIdx]
                    } else {
                        "(Not answered)"
                    }
                    val correctIdx = question.correctOptionIndex.toInt()
                    val refText = question.options.getOrNull(correctIdx) ?: question.correctAnswer
                    val isCorrect = selectedIdx >= 0 && selectedIdx == correctIdx
                    val awarded = if (isCorrect) question.marks.toDouble() else 0.0
                    preGraded[question.id] = GradedAnswerItem(
                        questionId = question.id,
                        questionType = question.questionType,
                        prompt = question.prompt,
                        userAnswer = userAnswerText,
                        referenceAnswer = refText,
                        maxMarks = question.marks,
                        awardedMarks = awarded,
                        feedback = when {
                            selectedIdx < 0 -> "No option selected."
                            isCorrect -> "Correct answer."
                            else -> "Incorrect option selected."
                        }
                    )
                }

                QuestionType.FILL_IN_BLANK -> {
                    val rawUser = textAnswers[question.id].orEmpty().trim()
                    if (rawUser.isEmpty()) {
                        preGraded[question.id] = GradedAnswerItem(
                            questionId = question.id,
                            questionType = question.questionType,
                            prompt = question.prompt,
                            userAnswer = "(Blank)",
                            referenceAnswer = question.correctAnswer,
                            maxMarks = question.marks,
                            awardedMarks = 0.0,
                            feedback = "No answer entered."
                        )
                    } else if (normalizeAnswer(rawUser) == normalizeAnswer(question.correctAnswer)) {
                        preGraded[question.id] = GradedAnswerItem(
                            questionId = question.id,
                            questionType = question.questionType,
                            prompt = question.prompt,
                            userAnswer = rawUser,
                            referenceAnswer = question.correctAnswer,
                            maxMarks = question.marks,
                            awardedMarks = question.marks.toDouble(),
                            feedback = "Exact match with reference answer."
                        )
                    } else {
                        aiQueue.add(question)
                    }
                }

                QuestionType.DESCRIPTIVE -> {
                    val rawUser = textAnswers[question.id].orEmpty().trim()
                    if (rawUser.isEmpty()) {
                        preGraded[question.id] = GradedAnswerItem(
                            questionId = question.id,
                            questionType = question.questionType,
                            prompt = question.prompt,
                            userAnswer = "(Not answered)",
                            referenceAnswer = question.correctAnswer,
                            maxMarks = question.marks,
                            awardedMarks = 0.0,
                            feedback = "No written response provided."
                        )
                    } else {
                        aiQueue.add(question)
                    }
                }
            }
        }

        // Batch evaluate non-blank descriptive and non-exact fill-in-the-blank answers with Gemini
        val aiGrades = mutableMapOf<String, Pair<Double, String>>()
        if (aiQueue.isNotEmpty()) {
            val chunks = aiQueue.chunked(10)
            for (chunk in chunks) {
                val payloadItems = JSONArray()
                chunk.forEach { q ->
                    payloadItems.put(
                        JSONObject().apply {
                            put("questionId", q.id)
                            put("questionType", q.questionType)
                            put("prompt", q.prompt)
                            put("referenceAnswer", q.correctAnswer)
                            put("maxMarks", q.marks)
                            put("candidateAnswer", textAnswers[q.id].orEmpty().trim())
                        }
                    )
                }

                val promptText = """
                    You are an objective technical exam evaluator grading candidate responses strictly against the Q&A PDF reference answers.
                    For each item below:
                    - For FILL_IN_BLANK: Award full maxMarks if semantically identical (minor plural, unit formatting, spacing, or casing differences). Award 0 if wrong concept.
                    - For DESCRIPTIVE: Compare the candidate's written description against the referenceAnswer key points. Award fair partial marks (in 0.5 steps, from 0.0 up to maxMarks) proportional to how many key facts and concepts from the referenceAnswer are accurately covered. Do not reward unrelated fluff.
                    - Keep feedback concise (1-2 sentences) in the same language as the prompt/answer (English or Bangla).

                    Items to evaluate JSON:
                    $payloadItems
                """.trimIndent()

                val response = try {
                    withTimeout(60_000L) {
                        generateGraderWithFallback(promptText)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "AI grading timed out", e)
                    throw IllegalStateException("AI grading timed out after 60 seconds. Please tap Submit Exam again.", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "AI grading failed", e)
                    throw IllegalStateException(
                        e.localizedMessage ?: "AI grading failed while evaluating written descriptions.",
                        e
                    )
                }

                val jsonStr = response.text
                    ?: throw IllegalStateException("AI grader returned an empty evaluation.")
                val root = JSONObject(jsonStr)
                val gradesArr = root.optJSONArray("grades") ?: JSONArray()
                for (i in 0 until gradesArr.length()) {
                    val obj = gradesArr.getJSONObject(i)
                    val qId = obj.optString("questionId")
                    val qObj = chunk.firstOrNull { it.id == qId } ?: continue
                    val rawMarks = obj.optDouble("awardedMarks", 0.0)
                    val clamped = roundToHalf(rawMarks.coerceIn(0.0, qObj.marks.toDouble()))
                    val feedback = obj.optString("feedback").ifBlank { "Evaluated against PDF reference answer." }
                    aiGrades[qId] = clamped to feedback
                }
            }
        }

        val orderedItems = questions.map { q ->
            preGraded[q.id] ?: run {
                val userAns = textAnswers[q.id].orEmpty().trim().ifBlank { "(Not answered)" }
                val (awarded, feedback) = aiGrades[q.id] ?: (0.0 to "Unable to match evaluation item.")
                GradedAnswerItem(
                    questionId = q.id,
                    questionType = q.questionType,
                    prompt = q.prompt,
                    userAnswer = userAns,
                    referenceAnswer = q.correctAnswer,
                    maxMarks = q.marks,
                    awardedMarks = awarded,
                    feedback = feedback
                )
            }
        }

        val totalMarks = questions.sumOf { it.marks.toInt() }.coerceAtLeast(1)
        val obtainedMarks = roundToOneDecimal(orderedItems.sumOf { it.awardedMarks }.coerceIn(0.0, totalMarks.toDouble()))
        val percentage = roundToOneDecimal((obtainedMarks / totalMarks.toDouble() * 100.0).coerceIn(0.0, 100.0))

        val mcqItems = orderedItems.filter { QuestionType.fromWire(it.questionType) == QuestionType.MCQ }
        val fillItems = orderedItems.filter { QuestionType.fromWire(it.questionType) == QuestionType.FILL_IN_BLANK }
        val descItems = orderedItems.filter { QuestionType.fromWire(it.questionType) == QuestionType.DESCRIPTIVE }

        ExamGradingSummary(
            obtainedMarks = obtainedMarks,
            totalMarks = totalMarks,
            percentage = percentage,
            mcqCorrect = mcqItems.count { it.awardedMarks >= it.maxMarks.toDouble() },
            mcqTotal = mcqItems.size,
            fillMarks = roundToOneDecimal(fillItems.sumOf { it.awardedMarks }),
            fillTotalMarks = fillItems.sumOf { it.maxMarks.toInt() },
            descMarks = roundToOneDecimal(descItems.sumOf { it.awardedMarks }),
            descTotalMarks = descItems.sumOf { it.maxMarks.toInt() },
            items = orderedItems
        )
    }

    private fun parseExtractionJson(rawJson: String): List<DraftExamQuestion> {
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("questions") ?: return emptyList()
        val list = mutableListOf<DraftExamQuestion>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val type = QuestionType.fromWire(obj.optString("questionType"))
            val prompt = obj.optString("prompt").trim()
            if (prompt.length < 3) continue

            val optionsJson = obj.optJSONArray("options")
            val rawOptions = mutableListOf<String>()
            if (optionsJson != null) {
                for (j in 0 until optionsJson.length()) {
                    val opt = optionsJson.optString(j).trim()
                    if (opt.isNotEmpty()) rawOptions.add(opt)
                }
            }

            val finalType = if (type == QuestionType.MCQ && rawOptions.size < 2) {
                QuestionType.DESCRIPTIVE
            } else {
                type
            }

            val finalOptions = if (finalType == QuestionType.MCQ) rawOptions.take(6) else emptyList()
            val rawIdx = obj.optInt("correctOptionIndex", -1)
            val finalIdx = if (finalType == QuestionType.MCQ) {
                rawIdx.coerceIn(0, finalOptions.lastIndex)
            } else {
                -1
            }

            val rawAnswer = obj.optString("correctAnswer").trim()
            val finalAnswer = when {
                finalType == QuestionType.MCQ && rawAnswer.isBlank() -> finalOptions.getOrElse(finalIdx) { "Option ${finalIdx + 1}" }
                rawAnswer.isBlank() -> "Reference answer extracted from PDF"
                else -> rawAnswer
            }

            val defaultMarks = if (finalType == QuestionType.DESCRIPTIVE) 5 else 1
            val marks = obj.optInt("marks", defaultMarks).coerceIn(1, 100)

            list.add(
                DraftExamQuestion(
                    tempId = UUID.randomUUID().toString(),
                    type = finalType,
                    prompt = prompt.take(2000),
                    options = finalOptions,
                    correctOptionIndex = finalIdx,
                    correctAnswer = finalAnswer.take(3000),
                    marks = marks
                )
            )
        }
        return list
    }

    private fun renderPageBitmap(page: PdfRenderer.Page): Bitmap {
        val longest = max(page.width, page.height).coerceAtLeast(1)
        val scale = MAX_RENDER_SIDE_PX.toFloat() / longest.toFloat()
        val targetW = (page.width * scale).toInt().coerceIn(600, MAX_RENDER_SIDE_PX)
        val targetH = (page.height * scale).toInt().coerceIn(600, MAX_RENDER_SIDE_PX)
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "qa_exam.pdf"
    }

    private fun normalizeAnswer(value: String): String {
        return value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0

    private fun roundToOneDecimal(value: Double): Double = round(value * 10.0) / 10.0

    private suspend fun generateExtractorWithFallback(
        bitmaps: List<Bitmap>,
        chunkPrompt: String
    ): com.google.firebase.ai.type.GenerateContentResponse {
        val attempts = listOf(
            "gemini-3.8-flash" to primaryExtractorModel,
            "gemini-3.5-flash-lite" to fallbackExtractorModel,
            "gemini-3.5-flash-lite" to fallbackExtractorModel
        )
        return runWithFallback(attempts, "extractor") { model ->
            model.generateContent(
                content {
                    bitmaps.forEach { bmp -> image(bmp) }
                    text(chunkPrompt)
                }
            )
        }
    }

    private suspend fun generateGraderWithFallback(
        promptText: String
    ): com.google.firebase.ai.type.GenerateContentResponse {
        val attempts = listOf(
            "gemini-3.8-flash" to primaryGraderModel,
            "gemini-3.5-flash-lite" to fallbackGraderModel,
            "gemini-3.5-flash-lite" to fallbackGraderModel
        )
        return runWithFallback(attempts, "grader") { model ->
            model.generateContent(promptText)
        }
    }

    private suspend fun runWithFallback(
        attempts: List<Pair<String, com.google.firebase.ai.GenerativeModel>>,
        role: String,
        block: suspend (com.google.firebase.ai.GenerativeModel) -> com.google.firebase.ai.type.GenerateContentResponse
    ): com.google.firebase.ai.type.GenerateContentResponse {
        var lastError: Exception? = null
        for ((index, entry) in attempts.withIndex()) {
            val (modelName, model) = entry
            try {
                return block(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val isTransient = isTransientDemandError(e)
                val hasNext = index < attempts.lastIndex
                Log.w(TAG, "Exam $role model $modelName failed (transient=$isTransient): ${e.message}")
                if (!isTransient || !hasNext) {
                    throw e
                }
                kotlinx.coroutines.delay(if (index == 0) 900L else 1800L)
            }
        }
        throw lastError ?: IllegalStateException("AI $role request failed.")
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
}
