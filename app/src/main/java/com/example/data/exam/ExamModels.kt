package com.example.data.exam

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties

enum class QuestionType(val wireValue: String, val label: String) {
    MCQ("MCQ", "MCQ"),
    FILL_IN_BLANK("FILL_IN_BLANK", "Fill in the Blank"),
    DESCRIPTIVE("DESCRIPTIVE", "Written Description");

    companion object {
        fun fromWire(raw: String?): QuestionType {
            return entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) }
                ?: DESCRIPTIVE
        }
    }
}

@Keep
@IgnoreExtraProperties
data class ExamPdfBatch(
    @DocumentId val id: String = "",
    val userId: String = "",
    val uploaderName: String = "",
    val title: String = "",
    val fileName: String = "",
    val pageCount: Long = 1L,
    val questionCount: Long = 0L,
    val mcqCount: Long = 0L,
    val fillCount: Long = 0L,
    val descriptiveCount: Long = 0L,
    val visibility: String = "EXAM_BANK",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ExamQuestion(
    @DocumentId val id: String = "",
    val userId: String = "",
    val batchId: String = "",
    val batchTitle: String = "",
    val questionType: String = QuestionType.MCQ.wireValue,
    val orderIndex: Long = 0L,
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val correctOptionIndex: Long = -1L,
    val correctAnswer: String = "",
    val marks: Long = 1L,
    val active: Boolean = true,
    val visibility: String = "EXAM_BANK",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val parsedType: QuestionType
        get() = QuestionType.fromWire(questionType)
}

@Keep
@IgnoreExtraProperties
data class ExamSubmission(
    @DocumentId val id: String = "",
    val userId: String = "",
    val candidateName: String = "",
    val phase: String = "",
    val factoryName: String = "",
    val obtainedMarks: Double = 0.0,
    val totalMarks: Long = 1L,
    val percentage: Double = 0.0,
    val questionCount: Long = 0L,
    val mcqCorrect: Long = 0L,
    val mcqTotal: Long = 0L,
    val fillMarks: Double = 0.0,
    val fillTotalMarks: Long = 0L,
    val descMarks: Double = 0.0,
    val descTotalMarks: Long = 0L,
    val status: String = "SUBMITTED",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GradedAnswerItem(
    val questionId: String = "",
    val questionType: String = QuestionType.MCQ.wireValue,
    val prompt: String = "",
    val userAnswer: String = "",
    val referenceAnswer: String = "",
    val maxMarks: Long = 1L,
    val awardedMarks: Double = 0.0,
    val feedback: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "questionId" to questionId,
        "questionType" to questionType,
        "prompt" to prompt,
        "userAnswer" to userAnswer,
        "referenceAnswer" to referenceAnswer,
        "maxMarks" to maxMarks,
        "awardedMarks" to awardedMarks,
        "feedback" to feedback
    )
}

@Keep
@IgnoreExtraProperties
data class ExamReview(
    @DocumentId val id: String = "",
    val submissionId: String = "",
    val candidateName: String = "",
    val phase: String = "",
    val factoryName: String = "",
    val obtainedMarks: Double = 0.0,
    val totalMarks: Long = 1L,
    val items: List<GradedAnswerItem> = emptyList(),
    val createdAt: Timestamp? = null
)

data class DraftExamQuestion(
    val tempId: String,
    val type: QuestionType,
    val prompt: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val correctAnswer: String,
    val marks: Int
)

data class ParsedPdfResult(
    val fileName: String,
    val suggestedTitle: String,
    val pageCount: Int,
    val questions: List<DraftExamQuestion>
)

data class RankedSubmission(
    val submission: ExamSubmission,
    val rank: Int,
    val totalCandidates: Int
)

fun computeCandidateRankings(submissions: List<ExamSubmission>): List<RankedSubmission> {
    if (submissions.isEmpty()) return emptyList()
    val sorted = submissions.sortedWith(
        compareByDescending<ExamSubmission> { it.percentage }
            .thenByDescending { it.obtainedMarks }
            .thenBy { it.createdAt?.seconds ?: Long.MAX_VALUE }
            .thenBy { it.createdAt?.nanoseconds ?: Int.MAX_VALUE }
            .thenBy { it.id }
    )
    val total = sorted.size
    var currentRank = 1
    return sorted.mapIndexed { index, sub ->
        if (index > 0) {
            val prev = sorted[index - 1]
            val tied = kotlin.math.abs(prev.percentage - sub.percentage) < 0.01 &&
                kotlin.math.abs(prev.obtainedMarks - sub.obtainedMarks) < 0.01
            if (!tied) {
                currentRank = index + 1
            }
        }
        RankedSubmission(
            submission = sub,
            rank = currentRank,
            totalCandidates = total
        )
    }
}

fun buildBatchWriteMap(
    userId: String,
    uploaderName: String,
    title: String,
    fileName: String,
    pageCount: Int,
    questions: List<DraftExamQuestion>
): Map<String, Any> {
    val safeTitle = title.trim().ifBlank { "Uploaded Q&A Set" }.take(150)
    val safeFileName = fileName.trim().ifBlank { "exam_qa.pdf" }.take(200)
    val safeUploader = uploaderName.trim().ifBlank { "Uploader" }.take(100)
    val mcqCount = questions.count { it.type == QuestionType.MCQ }
    val fillCount = questions.count { it.type == QuestionType.FILL_IN_BLANK }
    val descCount = questions.count { it.type == QuestionType.DESCRIPTIVE }
    return mapOf(
        "userId" to userId,
        "uploaderName" to safeUploader,
        "title" to safeTitle,
        "fileName" to safeFileName,
        "pageCount" to pageCount.coerceIn(1, 500),
        "questionCount" to questions.size.coerceIn(1, 300),
        "mcqCount" to mcqCount.coerceIn(0, 300),
        "fillCount" to fillCount.coerceIn(0, 300),
        "descriptiveCount" to descCount.coerceIn(0, 300),
        "visibility" to "EXAM_BANK",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

fun buildQuestionWriteMap(
    userId: String,
    batchId: String,
    batchTitle: String,
    orderIndex: Int,
    draft: DraftExamQuestion
): Map<String, Any> {
    val cleanPrompt = draft.prompt.trim().take(2000).let {
        if (it.length < 3) it.padEnd(3, '.') else it
    }
    val safeType = draft.type
    val safeOptions = if (safeType == QuestionType.MCQ) {
        draft.options.map { it.trim() }.filter { it.isNotEmpty() }.take(6).let { list ->
            if (list.size >= 2) list else listOf("Option A", "Option B")
        }
    } else {
        emptyList()
    }
    val safeCorrectIdx = if (safeType == QuestionType.MCQ) {
        draft.correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
    } else {
        -1
    }
    val rawAnswer = if (safeType == QuestionType.MCQ && safeCorrectIdx in safeOptions.indices) {
        draft.correctAnswer.trim().ifBlank { safeOptions[safeCorrectIdx] }
    } else {
        draft.correctAnswer.trim()
    }
    val safeAnswer = rawAnswer.ifBlank { "See uploaded Q&A PDF reference" }.take(3000)

    return mapOf(
        "userId" to userId,
        "batchId" to batchId,
        "batchTitle" to batchTitle.trim().ifBlank { "Uploaded Q&A Set" }.take(150),
        "questionType" to safeType.wireValue,
        "orderIndex" to orderIndex.coerceIn(0, 5000),
        "prompt" to cleanPrompt,
        "options" to safeOptions,
        "correctOptionIndex" to safeCorrectIdx,
        "correctAnswer" to safeAnswer,
        "marks" to draft.marks.coerceIn(1, 100),
        "active" to true,
        "visibility" to "EXAM_BANK",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}
