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
import com.example.data.exam.GradedAnswerItem
import com.example.data.exam.LocalExamReviewStore
import com.example.data.exam.QuestionType
import com.example.data.exam.RankedSubmission
import com.example.data.exam.SuperAdminEntry
import com.example.data.exam.computeCandidateRankings
import com.example.data.firebase.ExamStaffRole
import com.example.data.firebase.ExamStaffSession
import com.example.data.firebase.StaffAuthManager
import com.example.data.firebase.StaffSignInOutcome
import com.example.data.firebase.authStateFlow
import com.example.data.firebase.diagnoseAiFailure
import com.example.data.firebase.ensureFirebaseInitialized
import com.example.data.firebase.getFirebaseInitDiagnosticMessage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ExamViewModel"

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
    val gradedItems: List<GradedAnswerItem>
)

data class ExamUiState(
    val currentUserId: String = "",
    val staffSession: ExamStaffSession = ExamStaffSession(),
    val disabledCoordinatorUids: Set<String> = emptySet(),
    val superAdmins: List<SuperAdminEntry> = emptyList(),
    val isUpdatingSuperAdmins: Boolean = false,
    val showStaffSignInDialog: Boolean = false,
    val isSigningInStaff: Boolean = false,
    val staffSignInError: String? = null,
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

@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModel(
    private val repository: ExamRepository?,
    private val aiService: ExamAiService,
    private val reviewStore: LocalExamReviewStore,
    private val currentUserId: String,
    private val firebaseAuth: FirebaseAuth? = null,
    private val initError: String? = null
) : ViewModel() {

    private val savedProfile = reviewStore.loadCandidateProfile()
    private val _localReviews = MutableStateFlow(reviewStore.getReviews())

    private val _events = MutableSharedFlow<ExamEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _interactionState = MutableStateFlow(
        ExamUiState(
            currentUserId = currentUserId,
            candidateName = savedProfile.candidateName,
            phase = savedProfile.phase,
            factoryName = savedProfile.factoryName
        )
    )

    private val batchesStream: Flow<Result<List<ExamPdfBatch>>> = repository?.observePdfBatches()
        ?.map { Result.success(it) }
        ?.catch { e -> emit(handleStreamError("examPdfBatches", e)) }
        ?: flowOf(Result.failure(IllegalStateException(initError ?: "Cloud database unavailable.")))

    private val questionsStream: Flow<Result<List<ExamQuestion>>> = repository?.observeQuestions()
        ?.map { Result.success(it) }
        ?.catch { e -> emit(handleStreamError("examQuestions", e)) }
        ?: flowOf(Result.failure(IllegalStateException(initError ?: "Cloud database unavailable.")))

    private val submissionsStream: Flow<Result<List<ExamSubmission>>> = repository?.observeSubmissions()
        ?.map { Result.success(it) }
        ?.catch { e -> emit(handleStreamError("examSubmissions", e)) }
        ?: flowOf(Result.failure(IllegalStateException(initError ?: "Cloud database unavailable.")))

    private val firestoreDataState: Flow<ExamDataState> = if (initError != null || repository == null) {
        flowOf(ExamDataState.Error(initError ?: "Cloud database is not initialized in this APK build."))
    } else {
        combine(
            batchesStream,
            questionsStream,
            submissionsStream,
            _localReviews
        ) { batchesRes, questionsRes, submissionsRes, reviews ->
            val firstError = batchesRes.exceptionOrNull()
                ?: questionsRes.exceptionOrNull()
                ?: submissionsRes.exceptionOrNull()

            if (firstError != null) {
                ExamDataState.Error(firstError.localizedMessage ?: "Failed to load exam data from cloud.")
            } else {
                val batches = batchesRes.getOrDefault(emptyList())
                val questions = questionsRes.getOrDefault(emptyList())
                val submissions = submissionsRes.getOrDefault(emptyList())
                ExamDataState.Ready(
                    batches = batches,
                    questions = questions,
                    rankings = computeCandidateRankings(submissions),
                    userReviews = reviews
                )
            }
        }
    }

    private val staffSessionStream: Flow<ExamStaffSession> = (firebaseAuth?.authStateFlow() ?: flowOf(null))
        .flatMapLatest { user ->
            val baseSession = StaffAuthManager.sessionFromUser(user)
            if (!baseSession.isSignedIn || repository == null) {
                flowOf(baseSession)
            } else if (baseSession.isRootSuperAdmin) {
                flowOf(baseSession)
            } else {
                combine(
                    repository.observeSelfSuperAdmin(baseSession.email).catch { emit(false) },
                    repository.observeSelfRevoked(baseSession.uid).catch { emit(false) }
                ) { isDelegatedSuperAdmin, revoked ->
                    StaffAuthManager.sessionFromUser(
                        user = user,
                        isDelegatedSuperAdmin = isDelegatedSuperAdmin,
                        isRevoked = revoked
                    )
                }
            }
        }

    private val disabledCoordinatorsStream: Flow<Set<String>> = staffSessionStream
        .flatMapLatest { session ->
            if (session.isSuperAdmin && repository != null) {
                repository.observeDisabledCoordinators().catch { emit(emptySet()) }
            } else {
                flowOf(emptySet())
            }
        }

    private val superAdminsStream: Flow<List<SuperAdminEntry>> = staffSessionStream
        .flatMapLatest { session ->
            if (session.isSuperAdmin && repository != null) {
                repository.observeSuperAdmins().catch { emit(emptyList()) }
            } else {
                flowOf(emptyList())
            }
        }

    val uiState: StateFlow<ExamUiState> = combine(
        _interactionState,
        firestoreDataState,
        staffSessionStream,
        disabledCoordinatorsStream,
        superAdminsStream
    ) { local, remote, session, disabledUids, superAdmins ->
        local.copy(
            dataState = remote,
            staffSession = session,
            disabledCoordinatorUids = disabledUids,
            superAdmins = superAdmins
        )
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

    fun openStaffSignInDialog() {
        _interactionState.update {
            it.copy(showStaffSignInDialog = true, staffSignInError = null)
        }
    }

    fun dismissStaffSignInDialog() {
        if (_interactionState.value.isSigningInStaff) return
        _interactionState.update {
            it.copy(showStaffSignInDialog = false, staffSignInError = null)
        }
    }

    fun signInStaff(appContext: Context, usernameOrEmail: String, password: String) {
        if (_interactionState.value.isSigningInStaff) return
        viewModelScope.launch {
            _interactionState.update {
                it.copy(isSigningInStaff = true, staffSignInError = null)
            }
            when (val outcome = StaffAuthManager.signInWithUsernamePassword(appContext, usernameOrEmail, password)) {
                is StaffSignInOutcome.Success -> {
                    _interactionState.update {
                        it.copy(
                            isSigningInStaff = false,
                            showStaffSignInDialog = false,
                            staffSignInError = null
                        )
                    }
                    val message = if (outcome.session.isRootSuperAdmin) {
                        "Signed in as Root Super Admin (${outcome.session.email})."
                    } else {
                        "Signed in as ${outcome.session.email}."
                    }
                    _events.emit(ExamEvent.ShowMessage(message))
                }

                is StaffSignInOutcome.Error -> {
                    _interactionState.update {
                        it.copy(
                            isSigningInStaff = false,
                            staffSignInError = outcome.message
                        )
                    }
                }
            }
        }
    }

    fun signOutStaff() {
        StaffAuthManager.signOut()
        _events.tryEmit(ExamEvent.ShowMessage("Signed out of staff upload access."))
    }

    fun scanQaPdf(appContext: Context, uri: Uri) {
        if (_interactionState.value.isScanningPdf) return
        val session = uiState.value.staffSession
        if (!session.isSignedIn) {
            _events.tryEmit(ExamEvent.ShowMessage("Please sign in as Super Admin or Coordinator to upload Q&A PDFs."))
            return
        }
        if (!session.canUploadPdf) {
            _events.tryEmit(ExamEvent.ShowMessage("Your Coordinator upload access has been disabled by Super Admin."))
            return
        }
        viewModelScope.launch {
            _interactionState.update {
                it.copy(
                    isScanningPdf = true,
                    scanProgressText = "Opening Q&A PDF..."
                )
            }
            try {
                if (!ensureFirebaseInitialized(appContext)) {
                    throw IllegalStateException(getFirebaseInitDiagnosticMessage(appContext))
                }
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
                val message = diagnoseAiFailure(appContext, e)
                _interactionState.update { it.copy(isScanningPdf = false, scanProgressText = "") }
                _events.emit(ExamEvent.ShowMessage(message))
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
        val repo = repository ?: run {
            _events.tryEmit(ExamEvent.ShowMessage(initError ?: "Cloud database unavailable."))
            return
        }
        val session = uiState.value.staffSession
        if (!session.canUploadPdf) {
            _events.tryEmit(ExamEvent.ShowMessage("Staff upload permission required. Please sign in as Super Admin or Coordinator."))
            return
        }
        val draft = _interactionState.value.pendingDraft ?: return
        if (draft.isSaving || draft.questions.isEmpty()) return

        val handle = session.email.substringBefore('@').ifBlank { "staff" }
        val roleTag = if (session.isSuperAdmin) "Super Admin" else "Coordinator"
        val resolvedUploader = "$handle ($roleTag)"

        viewModelScope.launch {
            _interactionState.update { state ->
                state.copy(pendingDraft = state.pendingDraft?.copy(isSaving = true))
            }
            val result = repo.uploadBatchAndQuestions(
                participantId = session.uid,
                uploaderName = resolvedUploader,
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
        val repo = repository ?: return
        if (!canManage()) {
            _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can modify question visibility."))
            return
        }
        viewModelScope.launch {
            repo.setQuestionActive(question.id, !question.active).onFailure { error ->
                _events.emit(ExamEvent.ShowMessage(error.localizedMessage ?: "Unable to update question."))
            }
        }
    }

    fun deleteQuestion(question: ExamQuestion) {
        val repo = repository ?: return
        if (!canManage()) {
            _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can delete questions."))
            return
        }
        viewModelScope.launch {
            repo.deleteQuestion(question.id).fold(
                onSuccess = { _events.emit(ExamEvent.ShowMessage("Question removed.")) },
                onFailure = { _events.emit(ExamEvent.ShowMessage(it.localizedMessage ?: "Delete failed.")) }
            )
        }
    }

    fun deleteBatch(batch: ExamPdfBatch) {
        val repo = repository ?: return
        if (!canManage()) {
            _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can delete PDF batches."))
            return
        }
        val ready = uiState.value.dataState as? ExamDataState.Ready ?: return
        val qIds = ready.questions.filter { it.batchId == batch.id }.map { it.id }
        viewModelScope.launch {
            repo.deleteBatchWithQuestions(batch.id, qIds).fold(
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

    fun toggleCoordinatorBlocked(batch: ExamPdfBatch) {
        val repo = repository ?: return
        val session = uiState.value.staffSession
        if (!session.isSuperAdmin || batch.userId == session.uid || batch.userId.isBlank()) return
        val currentlyBlocked = batch.userId in uiState.value.disabledCoordinatorUids
        viewModelScope.launch {
            repo.setCoordinatorBlocked(
                targetUid = batch.userId,
                displayName = batch.uploaderName,
                adminUid = session.uid,
                blocked = !currentlyBlocked
            ).fold(
                onSuccess = {
                    val status = if (!currentlyBlocked) "blocked" else "restored"
                    _events.emit(ExamEvent.ShowMessage("Coordinator \"${batch.uploaderName}\" upload access $status."))
                },
                onFailure = { err ->
                    _events.emit(ExamEvent.ShowMessage(err.localizedMessage ?: "Unable to update coordinator access."))
                }
            )
        }
    }

    fun grantSuperAdmin(usernameOrEmail: String) {
        val repo = repository ?: return
        val session = uiState.value.staffSession
        if (!session.isSuperAdmin || _interactionState.value.isUpdatingSuperAdmins) {
            if (!session.isSuperAdmin) {
                _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can manage Super Admin access."))
            }
            return
        }

        val normalizedEmail = StaffAuthManager.normalizeUsernameOrEmail(usernameOrEmail)
        if (!StaffAuthManager.isValidStaffEmail(normalizedEmail)) {
            _events.tryEmit(ExamEvent.ShowMessage("Enter a valid username (e.g. coord1) or email (e.g. name@gmail.com)."))
            return
        }
        if (StaffAuthManager.isRootSuperAdminEmail(normalizedEmail)) {
            _events.tryEmit(ExamEvent.ShowMessage("${StaffAuthManager.SUPER_ADMIN_EMAIL} is already the permanent Root Super Admin."))
            return
        }
        if (uiState.value.superAdmins.any { it.email.equals(normalizedEmail, ignoreCase = true) || it.id.equals(normalizedEmail, ignoreCase = true) }) {
            _events.tryEmit(ExamEvent.ShowMessage("$normalizedEmail already has Super Admin access."))
            return
        }

        viewModelScope.launch {
            _interactionState.update { it.copy(isUpdatingSuperAdmins = true) }
            repo.grantSuperAdmin(
                targetEmail = normalizedEmail,
                addedByEmail = session.email,
                addedByUid = session.uid
            ).fold(
                onSuccess = {
                    _interactionState.update { it.copy(isUpdatingSuperAdmins = false) }
                    _events.emit(ExamEvent.ShowMessage("$normalizedEmail is now a Super Admin."))
                },
                onFailure = { err ->
                    _interactionState.update { it.copy(isUpdatingSuperAdmins = false) }
                    _events.emit(ExamEvent.ShowMessage(err.localizedMessage ?: "Failed to grant Super Admin role."))
                }
            )
        }
    }

    fun revokeSuperAdmin(targetEmail: String) {
        val repo = repository ?: return
        val session = uiState.value.staffSession
        if (!session.isSuperAdmin || _interactionState.value.isUpdatingSuperAdmins) {
            if (!session.isSuperAdmin) {
                _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can change Super Admin access."))
            }
            return
        }

        val cleanEmail = targetEmail.trim().lowercase()
        if (cleanEmail.isEmpty()) return
        if (StaffAuthManager.isRootSuperAdminEmail(cleanEmail)) {
            _events.tryEmit(ExamEvent.ShowMessage("Cannot remove the permanent Root Super Admin (${StaffAuthManager.SUPER_ADMIN_EMAIL})."))
            return
        }

        viewModelScope.launch {
            _interactionState.update { it.copy(isUpdatingSuperAdmins = true) }
            repo.revokeSuperAdmin(cleanEmail).fold(
                onSuccess = {
                    _interactionState.update { it.copy(isUpdatingSuperAdmins = false) }
                    _events.emit(ExamEvent.ShowMessage("$cleanEmail changed back to Coordinator (Upload Only)."))
                },
                onFailure = { err ->
                    _interactionState.update { it.copy(isUpdatingSuperAdmins = false) }
                    _events.emit(ExamEvent.ShowMessage(err.localizedMessage ?: "Failed to update Super Admin role."))
                }
            )
        }
    }

    fun deleteSubmission(submissionId: String, candidateName: String) {
        val repo = repository ?: return
        if (!canManage()) {
            _events.tryEmit(ExamEvent.ShowMessage("Only Super Admin can delete submissions."))
            return
        }
        viewModelScope.launch {
            repo.deleteSubmission(submissionId).fold(
                onSuccess = { _events.emit(ExamEvent.ShowMessage("Removed submission for $candidateName.")) },
                onFailure = { _events.emit(ExamEvent.ShowMessage(it.localizedMessage ?: "Failed to remove submission.")) }
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

        reviewStore.saveCandidateProfile(name, phase, factory)

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
        val repo = repository ?: run {
            _events.tryEmit(ExamEvent.ShowMessage(initError ?: "Cloud database unavailable."))
            return
        }
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

                val saveResult = repo.submitExamResult(
                    participantId = currentUserId,
                    candidateName = session.candidateName,
                    phase = session.phase,
                    factoryName = session.factoryName,
                    grading = summary
                )

                saveResult.fold(
                    onSuccess = { submissionId ->
                        val localReview = ExamReview(
                            id = submissionId,
                            submissionId = submissionId,
                            candidateName = session.candidateName,
                            phase = session.phase,
                            factoryName = session.factoryName,
                            obtainedMarks = summary.obtainedMarks,
                            totalMarks = summary.totalMarks.toLong(),
                            items = summary.items
                        )
                        reviewStore.saveReview(localReview)
                        _localReviews.value = reviewStore.getReviews()

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
                val message = diagnoseAiFailure(null, e)
                _interactionState.update { state ->
                    state.copy(activeSession = state.activeSession?.copy(isSubmitting = false))
                }
                _events.emit(
                    ExamEvent.ShowMessage(message)
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
        val found = _localReviews.value.firstOrNull { it.submissionId == submissionId || it.id == submissionId }
        if (found != null) {
            _interactionState.update { it.copy(inspectedReview = found) }
        } else {
            _events.tryEmit(ExamEvent.ShowMessage("Detailed answer review is only stored on the submitting device."))
        }
    }

    fun dismissInspectedReview() {
        _interactionState.update { it.copy(inspectedReview = null) }
    }

    fun canManage(): Boolean = uiState.value.staffSession.isSuperAdmin

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
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val reviewStore = LocalExamReviewStore(application)
            val participantId = reviewStore.getOrCreateParticipantId()

            val initialized = ensureFirebaseInitialized(application)
            val dbResult = if (!initialized) {
                Result.failure(
                    IllegalStateException(getFirebaseInitDiagnosticMessage(application))
                )
            } else {
                runCatching {
                    val databaseId = application.getString(R.string.firestore_database_id).trim()
                    if (databaseId.isEmpty()) {
                        FirebaseFirestore.getInstance()
                    } else {
                        FirebaseFirestore.getInstance(databaseId)
                    }
                }
            }

            val firebaseAuth = if (initialized) {
                runCatching { FirebaseAuth.getInstance() }.getOrNull()
            } else {
                null
            }

            val db = dbResult.getOrNull()
            val errorMsg = dbResult.exceptionOrNull()?.let { err ->
                Log.e(TAG, "Failed to obtain FirebaseFirestore instance", err)
                err.localizedMessage ?: "Failed to initialize Exam cloud sync."
            }

            return ExamViewModel(
                repository = db?.let { ExamRepository(it) },
                aiService = ExamAiService(),
                reviewStore = reviewStore,
                currentUserId = participantId,
                firebaseAuth = firebaseAuth,
                initError = errorMsg
            ) as T
        }
    }
}
