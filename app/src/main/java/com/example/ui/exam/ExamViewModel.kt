package com.example.ui.exam

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.R
import com.example.data.exam.DraftExamQuestion
import com.example.data.exam.ExamAiService
import com.example.data.exam.ExamGradingSummary
import com.example.data.exam.ExamPdfBatch
import com.example.data.exam.ExamQuestion
import com.example.data.exam.ExamRepository
import com.example.data.exam.ExamReview
import com.example.data.exam.ExamSubmission
import com.example.data.exam.QuestionType
import com.example.data.exam.RankedSubmission
import com.example.data.exam.computeCandidateRankings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ExamViewModel"
private const val ADMIN_EMAIL = "qureshialrajin@gmail.com"

enum class ExamHubTab(val label: String) {
    ATTEND("Attend Exam"),
    RANKINGS("Rankings"),
    QUESTION_BANK("Q&A PDF Bank")
}

sealed interface ExamDataState {
    data object Loading : ExamDataState
    data class Ready(
        val batches: List<ExamPdfBatch>,
        val questions: List<ExamQuestion>,
        val rankings: List<RankedSubmission>,
        val userReviews: List<ExamReview>
    ) : ExamDataState

    data class Error(val message: String) : ExamDataState
}

data class ActiveExamSession(
    val candidateName: String,
    val phase: String,
    val factoryName: String,
    val questions: List<ExamQuestion>,
    val currentIndex: Int = 0,
    val mcqSelections: Map<String, Int> = emptyMap(),
    val textAnswers: Map<String, String> = emptyMap(),
    val isSubmitting: Boolean = false
)

data class PendingPdfUploadDraft(
    val fileName: String,
    val pageCount: Int,
    val title: String,
    val questions: List<DraftExamQuestion>,
    val isSaving: Boolean = false
)

data class ExamResultView(
    val submissionId: String,
    val candidateName: String,
    val phase: String,
    val factoryName: String,
    val obtainedMarks: Double,
    val totalMarks: Int,
    val percentage: Double,
    val mcqCorrect: Int,
    val mcqTotal: Int,
    val fillMarks: Double,
    val fillTotalMarks: Int,
    val descMarks: Double,
    val descTotalMarks: Int,
    val gradedItems: List<com.example.data.exam.GradedAnswerItem>
)

data class ExamUiState(
    val currentUserId: String = "",
    val uploaderName: String = "",
    val isAdmin: Boolean = false,
    val selectedTab: ExamHubTab = ExamHubTab.ATTEND,
    val dataState: ExamDataState = ExamDataState.Loading,
    val candidateName: String = "",
    val phase: String = "",
    val factoryName: String = "",
    val selectedBatchFilterId: String? = null,
    val bankBatchFilterId: String? = null,
    val isScanningPdf: Boolean = false,
    val scanProgressText: String = "",
    val pendingDraft: PendingPdfUploadDraft? = null,
    val activeSession: ActiveExamSession? = null,
    val latestResult: ExamResultView? = null,
    val inspectedReview: ExamReview? = null
)

sealed interface ExamEvent {
    data class ShowMessage(val text: String) : ExamEvent
}

class ExamViewModel(
    private val repository: ExamRepository,
    private val aiService: ExamAiService,
    private val currentUserId: String,
    private val uploaderName: String,
    userEmail: String?
) : ViewModel() {

    private val isAdminUser = userEmail.equals(ADMIN_EMAIL, ignoreCase = true)

    private val _events = MutableSharedFlow<ExamEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _interactionState = MutableStateFlow(
        ExamUiState(
            currentUserId = currentUserId,
            uploaderName = uploaderName,
            isAdmin = isAdminUser,
            candidateName = uploaderName.takeIf { it.isNotBlank() && it != "Google User" }.orEmpty()
        )
    )

    private val batchesStream: Flow<Result<List<ExamPdfBatch>>> = repository.observePdfBatches()
        .map { Result.success(it) }
        .catch { e -> emit(handleStreamError("examPdfBatches", e)) }

    private val questionsStream: Flow<Result<List<ExamQuestion>>> = repository.observeQuestions()
        .map { Result.success(it) }
        .catch { e -> emit(handleStreamError("examQuestions", e)) }

    private val submissionsStream: Flow<Result<List<ExamSubmission>>> = repository.observeSubmissions()
        .map { Result.success(it) }
        .catch { e -> emit(handleStreamError("examSubmissions", e)) }

    private val reviewsStream: Flow<Result<List<ExamReview>>> = repository.observeUserReviews(currentUserId)
        .map { Result.success(it) }
        .catch { e -> emit(handleStreamError("examReviews", e)) }

    private val firestoreDataState: Flow<ExamDataState> = combine(
        batchesStream,
        questionsStream,
        submissionsStream,
        reviewsStream
    ) { batchesRes, questionsRes, submissionsRes, reviewsRes ->
        val firstError = batchesRes.exceptionOrNull()
            ?: questionsRes.exceptionOrNull()
            ?: submissionsRes.exceptionOrNull()
            ?: reviewsRes.exceptionOrNull()

        if (firstError != null) {
            ExamDataState.Error(firstError.localizedMessage ?: "Failed to load exam data from cloud.")
        } else {
            val batches = batchesRes.getOrDefault(emptyList())
            val questions = questionsRes.getOrDefault(emptyList())
            val submissions = submissionsRes.getOrDefault(emptyList())
            val reviews = reviewsRes.getOrDefault(emptyList())
            ExamDataState.Ready(
                batches = batches,
                questions = questions,
                rankings = computeCandidateRankings(submissions),
                userReviews = reviews
            )
        }
    }

    val uiState: StateFlow<ExamUiState> = combine(
        _interactionState,
        firestoreDataState
    ) { local, remote ->
        local.copy(dataState = remote)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _interactionState.value
    )

    fun selectTab(tab: ExamHubTab) {
        _interactionState.update { it.copy(selectedTab = tab) }
    }

    fun updateCandidateName(value: String) {
        _interactionState.update { it.copy(candidateName = value.take(100)) }
    }

    fun updatePhase(value: String) {
        _interactionState.update { it.copy(phase = value.take(100)) }
    }

    fun updateFactoryName(value: String) {
        _interactionState.update { it.copy(factoryName = value.take(150)) }
    }

    fun selectExamBatchFilter(batchId: String?) {
        _interactionState.update { it.copy(selectedBatchFilterId = batchId) }
    }

    fun selectBankBatchFilter(batchId: String?) {
        _interactionState.update { it.copy(bankBatchFilterId = batchId) }
    }

    fun scanQaPdf(appContext: Context, uri: Uri) {
        if (_interactionState.value.isScanningPdf) return
        viewModelScope.launch {
            _interactionState.update {
                it.copy(
                    isScanningPdf = true,
                    scanProgressText = "Opening Q&A PDF..."
                )
            }
            try {
                val parsed = aiService.parseQaPdfUri(appContext.applicationContext, uri) { progress ->
                    _interactionState.update { it.copy(scanProgressText = progress) }
                }
                _interactionState.update {
                    it.copy(
                        isScanningPdf = false,
                        scanProgressText = "",
                        pendingDraft = PendingPdfUploadDraft(
                            fileName = parsed.fileName,
                            pageCount = parsed.pageCount,
                            title = parsed.suggestedTitle,
                            questions = parsed.questions
                        )
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "PDF AI scan timed out", e)
                _interactionState.update { it.copy(isScanningPdf = false, scanProgressText = "") }
                _events.emit(ExamEvent.ShowMessage("AI scan timed out. Try a smaller PDF or retry."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "PDF AI scan failed", e)
                _interactionState.update { it.copy(isScanningPdf = false, scanProgressText = "") }
                _events.emit(ExamEvent.ShowMessage(e.localizedMessage ?: "Failed to scan Q&A PDF."))
            }
        }
    }

    fun updateDraftTitle(title: String) {
        _interactionState.update { state ->
            state.copy(pendingDraft = state.pendingDraft?.copy(title = title.take(150)))
        }
    }

    fun updateDraftQuestionMarks(tempId: String, marks: Int) {
        val clamped = marks.coerceIn(1, 100)
        _interactionState.update { state ->
            val draft = state.pendingDraft ?: return@update state
            state.copy(
                pendingDraft = draft.copy(
                    questions = draft.questions.map { q ->
                        if (q.tempId == tempId) q.copy(marks = clamped) else q
                    }
                )
            )
        }
    }

    fun updateDraftQuestionType(tempId: String, newType: QuestionType) {
        _interactionState.update { state ->
            val draft = state.pendingDraft ?: return@update state
            state.copy(
                pendingDraft = draft.copy(
                    questions = draft.questions.map { q ->
                        if (q.tempId != tempId) q
                        else {
                            val opts = if (newType == QuestionType.MCQ && q.options.size < 2) {
                                listOf("Option A", "Option B", "Option C", "Option D")
                            } else if (newType == QuestionType.MCQ) q.options else emptyList()
                            val idx = if (newType == QuestionType.MCQ) {
                                q.correctOptionIndex.coerceIn(0, opts.lastIndex)
                            } else -1
                            q.copy(type = newType, options = opts, correctOptionIndex = idx)
                        }
                    }
                )
            )
        }
    }

    fun removeDraftQuestion(tempId: String) {
        _interactionState.update { state ->
            val draft = state.pendingDraft ?: return@update state
            state.copy(
                pendingDraft = draft.copy(
                    questions = draft.questions.filterNot { it.tempId == tempId }
                )
            )
        }
    }

    fun dismissPendingDraft() {
        _interactionState.update { it.copy(pendingDraft = null) }
    }

    fun confirmUploadDraft() {
        val draft = _interactionState.value.pendingDraft ?: return
        if (draft.isSaving || draft.questions.isEmpty()) return

        viewModelScope.launch {
            _interactionState.update { state ->
                state.copy(pendingDraft = state.pendingDraft?.copy(isSaving = true))
            }
            val result = repository.uploadBatchAndQuestions(
                userId = currentUserId,
                uploaderName = uploaderName,
                title = draft.title.ifBlank { draft.fileName },
                fileName = draft.fileName,
                pageCount = draft.pageCount,
                questions = draft.questions
            )
            result.fold(
                onSuccess = {
                    _interactionState.update {
                        it.copy(
                            pendingDraft = null,
                            selectedTab = ExamHubTab.QUESTION_BANK
                        )
                    }
                    _events.emit(
                        ExamEvent.ShowMessage("Added ${draft.questions.size} questions from \"${draft.title}\" to Exam Bank.")
                    )
                },
                onFailure = { error ->
                    _interactionState.update { state ->
                        state.copy(pendingDraft = state.pendingDraft?.copy(isSaving = false))
                    }
                    _events.emit(
                        ExamEvent.ShowMessage(error.localizedMessage ?: "Failed to save questions.")
                    )
                }
            )
        }
    }

    fun toggleQuestionActive(question: ExamQuestion) {
        if (!canManage(question.userId)) return
        viewModelScope.launch {
            repository.setQuestionActive(question.id, !question.active).onFailure { error ->
                _events.emit(ExamEvent.ShowMessage(error.localizedMessage ?: "Unable to update question."))
            }
        }
    }

    fun deleteQuestion(question: ExamQuestion) {
        if (!canManage(question.userId)) return
        viewModelScope.launch {
            repository.deleteQuestion(question.id).fold(
                onSuccess = { _events.emit(ExamEvent.ShowMessage("Question removed.")) },
                onFailure = { _events.emit(ExamEvent.ShowMessage(it.localizedMessage ?: "Delete failed.")) }
            )
        }
    }

    fun deleteBatch(batch: ExamPdfBatch) {
        if (!canManage(batch.userId)) return
        val ready = uiState.value.dataState as? ExamDataState.Ready ?: return
        val qIds = ready.questions.filter { it.batchId == batch.id }.map { it.id }
        viewModelScope.launch {
            repository.deleteBatchWithQuestions(batch.id, qIds).fold(
                onSuccess = {
                    _interactionState.update { state ->
                        state.copy(
                            selectedBatchFilterId = if (state.selectedBatchFilterId == batch.id) null else state.selectedBatchFilterId,
                            bankBatchFilterId = if (state.bankBatchFilterId == batch.id) null else state.bankBatchFilterId
                        )
                    }
                    _events.emit(ExamEvent.ShowMessage("Deleted \"${batch.title}\" (${qIds.size} questions)."))
                },
                onFailure = {
                    _events.emit(ExamEvent.ShowMessage(it.localizedMessage ?: "Unable to delete PDF batch."))
                }
            )
        }
    }

    fun startExam() {
        val state = uiState.value
        val ready = state.dataState as? ExamDataState.Ready ?: return
        val name = state.candidateName.trim()
        val phase = state.phase.trim()
        val factory = state.factoryName.trim()

        if (name.length < 2) {
            _events.tryEmit(ExamEvent.ShowMessage("Please enter your full Name (at least 2 characters)."))
            return
        }
        if (phase.isEmpty()) {
            _events.tryEmit(ExamEvent.ShowMessage("Please enter your Phase (e.g., AQC - 10th Phase)."))
            return
        }
        if (factory.isEmpty()) {
            _events.tryEmit(ExamEvent.ShowMessage("Please enter your Factory Name."))
            return
        }

        val activeQuestions = ready.questions
            .filter { it.active }
            .let { list ->
                val filterId = state.selectedBatchFilterId
                if (filterId != null) list.filter { it.batchId == filterId } else list
            }
            .take(500)

        if (activeQuestions.isEmpty()) {
            _events.tryEmit(ExamEvent.ShowMessage("No active questions yet. Upload a Q&A PDF in Q&A PDF Bank first."))
            return
        }

        _interactionState.update {
            it.copy(
                latestResult = null,
                inspectedReview = null,
                activeSession = ActiveExamSession(
                    candidateName = name,
                    phase = phase,
                    factoryName = factory,
                    questions = activeQuestions
                )
            )
        }
    }

    fun selectMcqOption(questionId: String, optionIndex: Int) {
        _interactionState.update { state ->
            val session = state.activeSession ?: return@update state
            if (session.isSubmitting) return@update state
            state.copy(
                activeSession = session.copy(
                    mcqSelections = session.mcqSelections + (questionId to optionIndex)
                )
            )
        }
    }

    fun updateTextAnswer(questionId: String, answer: String) {
        _interactionState.update { state ->
            val session = state.activeSession ?: return@update state
            if (session.isSubmitting) return@update state
            state.copy(
                activeSession = session.copy(
                    textAnswers = session.textAnswers + (questionId to answer.take(4000))
                )
            )
        }
    }

    fun jumpToQuestion(index: Int) {
        _interactionState.update { state ->
            val session = state.activeSession ?: return@update state
            val clamped = index.coerceIn(0, session.questions.lastIndex)
            state.copy(activeSession = session.copy(currentIndex = clamped))
        }
    }

    fun cancelExamSession() {
        _interactionState.update { it.copy(activeSession = null) }
    }

    fun submitExam() {
        val session = _interactionState.value.activeSession ?: return
        if (session.isSubmitting) return

        viewModelScope.launch {
            _interactionState.update { state ->
                state.copy(activeSession = state.activeSession?.copy(isSubmitting = true))
            }

            try {
                val summary: ExamGradingSummary = aiService.gradeExamAnswers(
                    questions = session.questions,
                    mcqSelections = session.mcqSelections,
                    textAnswers = session.textAnswers
                )

                val saveResult = repository.submitExamResult(
                    userId = currentUserId,
                    candidateName = session.candidateName,
                    phase = session.phase,
                    factoryName = session.factoryName,
                    grading = summary
                )

                saveResult.fold(
                    onSuccess = { submissionId ->
                        _interactionState.update {
                            it.copy(
                                activeSession = null,
                                latestResult = ExamResultView(
                                    submissionId = submissionId,
                                    candidateName = session.candidateName,
                                    phase = session.phase,
                                    factoryName = session.factoryName,
                                    obtainedMarks = summary.obtainedMarks,
                                    totalMarks = summary.totalMarks,
                                    percentage = summary.percentage,
                                    mcqCorrect = summary.mcqCorrect,
                                    mcqTotal = summary.mcqTotal,
                                    fillMarks = summary.fillMarks,
                                    fillTotalMarks = summary.fillTotalMarks,
                                    descMarks = summary.descMarks,
                                    descTotalMarks = summary.descTotalMarks,
                                    gradedItems = summary.items
                                )
                            )
                        }
                    },
                    onFailure = { error ->
                        _interactionState.update { state ->
                            state.copy(activeSession = state.activeSession?.copy(isSubmitting = false))
                        }
                        _events.emit(
                            ExamEvent.ShowMessage(error.localizedMessage ?: "Failed to save exam result.")
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exam grading or submission error", e)
                _interactionState.update { state ->
                    state.copy(activeSession = state.activeSession?.copy(isSubmitting = false))
                }
                _events.emit(
                    ExamEvent.ShowMessage(e.localizedMessage ?: "Error grading exam answers.")
                )
            }
        }
    }

    fun closeLatestResult(openRankingsTab: Boolean = false) {
        _interactionState.update {
            it.copy(
                latestResult = null,
                selectedTab = if (openRankingsTab) ExamHubTab.RANKINGS else it.selectedTab
            )
        }
    }

    fun inspectOwnReview(submissionId: String) {
        val ready = uiState.value.dataState as? ExamDataState.Ready ?: return
        val found = ready.userReviews.firstOrNull { it.submissionId == submissionId || it.id == submissionId }
        if (found != null) {
            _interactionState.update { it.copy(inspectedReview = found) }
        } else {
            _events.tryEmit(ExamEvent.ShowMessage("Detailed answer breakdown is only visible to the submitting account."))
        }
    }

    fun dismissInspectedReview() {
        _interactionState.update { it.copy(inspectedReview = null) }
    }

    fun canManage(ownerId: String): Boolean = isAdminUser || ownerId == currentUserId

    private fun <T> handleStreamError(collection: String, error: Throwable): Result<List<T>> {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, "Firestore stream error on $collection", root)
        return Result.failure(root)
    }

    class Factory(
        private val application: Application,
        private val currentUserId: String,
        private val uploaderName: String,
        private val userEmail: String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val databaseId = application.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            return ExamViewModel(
                repository = ExamRepository(db),
                aiService = ExamAiService(),
                currentUserId = currentUserId,
                uploaderName = uploaderName,
                userEmail = userEmail
            ) as T
        }
    }
}
