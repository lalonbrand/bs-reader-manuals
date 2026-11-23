package com.example.data.exam

import com.example.data.firebase.OperationType
import com.example.data.firebase.awaitTask
import com.example.data.firebase.handleFirestoreError
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ExamRepository(
    private val db: FirebaseFirestore
) {

    fun observePdfBatches(): Flow<List<ExamPdfBatch>> =
        db.collection("examPdfBatches")
            .whereEqualTo("visibility", "EXAM_BANK")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ExamPdfBatch::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }

    fun observeQuestions(): Flow<List<ExamQuestion>> =
        db.collection("examQuestions")
            .whereEqualTo("visibility", "EXAM_BANK")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ExamQuestion::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedWith(
                    compareBy<ExamQuestion> { it.createdAt ?: Timestamp(0, 0) }
                        .thenBy { it.orderIndex }
                        .thenBy { it.id }
                )
            }

    fun observeSubmissions(): Flow<List<ExamSubmission>> =
        db.collection("examSubmissions")
            .whereEqualTo("status", "SUBMITTED")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ExamSubmission::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    suspend fun uploadBatchAndQuestions(
        participantId: String,
        uploaderName: String,
        title: String,
        fileName: String,
        pageCount: Int,
        questions: List<DraftExamQuestion>
    ): Result<String> {
        val batchId = "batch_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return try {
            require(participantId.isNotBlank()) { "Participant ID is missing." }
            require(questions.isNotEmpty()) { "At least one question is required." }

            val trimmedQuestions = questions.take(300)
            val batchDocRef = db.collection("examPdfBatches").document(batchId)
            val batchData = buildBatchWriteMap(
                userId = participantId,
                uploaderName = uploaderName,
                title = title,
                fileName = fileName,
                pageCount = pageCount,
                questions = trimmedQuestions
            )

            // Firestore batches support up to 500 operations; commit batch doc + up to 300 questions atomically
            val writeBatch = db.batch()
            writeBatch.set(batchDocRef, batchData)
            trimmedQuestions.forEachIndexed { index, draft ->
                val qId = "q_${batchId.removePrefix("batch_")}_${index}_${UUID.randomUUID().toString().take(6)}"
                val qRef = db.collection("examQuestions").document(qId)
                writeBatch.set(
                    qRef,
                    buildQuestionWriteMap(
                        userId = participantId,
                        batchId = batchId,
                        batchTitle = title,
                        orderIndex = index,
                        draft = draft
                    )
                )
            }
            writeBatch.commit().awaitTask()
            Result.success(batchId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "examPdfBatches/$batchId")
            Result.failure(e)
        }
    }

    suspend fun setQuestionActive(questionId: String, active: Boolean): Result<Unit> {
        return try {
            require(questionId.isNotBlank())
            db.collection("examQuestions")
                .document(questionId)
                .update(
                    mapOf(
                        "active" to active,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "examQuestions/$questionId")
            Result.failure(e)
        }
    }

    suspend fun deleteQuestion(questionId: String): Result<Unit> {
        return try {
            require(questionId.isNotBlank())
            db.collection("examQuestions").document(questionId).delete().awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "examQuestions/$questionId")
            Result.failure(e)
        }
    }

    suspend fun deleteBatchWithQuestions(
        batchId: String,
        questionIdsInBatch: List<String>
    ): Result<Unit> {
        return try {
            require(batchId.isNotBlank())
            val writeBatch = db.batch()
            questionIdsInBatch.take(400).forEach { qId ->
                writeBatch.delete(db.collection("examQuestions").document(qId))
            }
            writeBatch.delete(db.collection("examPdfBatches").document(batchId))
            writeBatch.commit().awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "examPdfBatches/$batchId")
            Result.failure(e)
        }
    }

    suspend fun submitExamResult(
        participantId: String,
        candidateName: String,
        phase: String,
        factoryName: String,
        grading: ExamGradingSummary
    ): Result<String> {
        val submissionId = "sub_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        return try {
            require(participantId.isNotBlank()) { "Participant ID is missing." }
            val safeName = candidateName.trim().take(100)
            val safePhase = phase.trim().take(100)
            val safeFactory = factoryName.trim().take(150)
            require(safeName.length >= 2) { "Candidate Name must be at least 2 characters." }
            require(safePhase.isNotEmpty()) { "Phase is required." }
            require(safeFactory.isNotEmpty()) { "Factory Name is required." }

            val submissionMap = mapOf(
                "userId" to participantId,
                "candidateName" to safeName,
                "phase" to safePhase,
                "factoryName" to safeFactory,
                "obtainedMarks" to grading.obtainedMarks,
                "totalMarks" to grading.totalMarks,
                "percentage" to grading.percentage,
                "questionCount" to grading.items.size,
                "mcqCorrect" to grading.mcqCorrect,
                "mcqTotal" to grading.mcqTotal,
                "fillMarks" to grading.fillMarks,
                "fillTotalMarks" to grading.fillTotalMarks,
                "descMarks" to grading.descMarks,
                "descTotalMarks" to grading.descTotalMarks,
                "status" to "SUBMITTED",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.collection("examSubmissions")
                .document(submissionId)
                .set(submissionMap)
                .awaitTask()

            Result.success(submissionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "examSubmissions/$submissionId")
            Result.failure(e)
        }
    }
}
