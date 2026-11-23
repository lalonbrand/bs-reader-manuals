package com.example.ui.exam

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.exam.ExamPdfBatch
import com.example.data.exam.ExamQuestion
import com.example.data.exam.ExamReview
import com.example.data.exam.QuestionType
import com.example.data.exam.RankedSubmission
import com.example.ui.theme.CosmicGlassBackdrop
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.SoftPinkOrchid
import com.example.ui.theme.glassPanel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamCenterRoot(
    viewModel: ExamViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.scanQaPdf(context.applicationContext, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ExamEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    // Active exam taking full-screen mode
    uiState.activeSession?.let { activeSession ->
        ExamTakingScreen(
            session = activeSession,
            onSelectMcq = viewModel::selectMcqOption,
            onUpdateText = viewModel::updateTextAnswer,
            onJumpTo = viewModel::jumpToQuestion,
            onSubmitExam = viewModel::submitExam,
            onExitSession = viewModel::cancelExamSession
        )
        return
    }

    // Post-submission result & rank screen
    uiState.latestResult?.let { latestResult ->
        val rankings = (uiState.dataState as? ExamDataState.Ready)?.rankings.orEmpty()
        ExamResultScreen(
            result = latestResult,
            rankings = rankings,
            onDone = { viewModel.closeLatestResult(openRankingsTab = false) },
            onViewLeaderboard = { viewModel.closeLatestResult(openRankingsTab = true) }
        )
        return
    }

    CosmicGlassBackdrop {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 960.dp)
                        .align(Alignment.TopCenter)
                ) {
                    ExamHubHeader(
                        onNavigateBack = onNavigateBack
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(ExamHubTab.entries) { tab ->
                            val selected = uiState.selectedTab == tab
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                label = { Text(tab.label) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = Color.White.copy(alpha = 0.20f),
                                    selectedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier.testTag("exam_tab_${tab.name}")
                            )
                        }
                    }

                    when (val dataState = uiState.dataState) {
                        is ExamDataState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = SoftCyan)
                                    Text(
                                        text = "Syncing question bank & candidate rankings...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        is ExamDataState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier
                                        .glassPanel(shape = RoundedCornerShape(22.dp))
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "Cloud Sync Issue",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = ErrorCoral
                                    )
                                    Text(
                                        text = dataState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Button(
                                        onClick = onNavigateBack,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text("Back to Bookshelf")
                                    }
                                }
                            }
                        }

                        is ExamDataState.Ready -> {
                            when (uiState.selectedTab) {
                                ExamHubTab.ATTEND -> AttendExamTab(
                                    uiState = uiState,
                                    ready = dataState,
                                    onNameChange = viewModel::updateCandidateName,
                                    onPhaseChange = viewModel::updatePhase,
                                    onFactoryChange = viewModel::updateFactoryName,
                                    onSelectBatchFilter = viewModel::selectExamBatchFilter,
                                    onStartExam = viewModel::startExam,
                                    onOpenUploadTab = { viewModel.selectTab(ExamHubTab.QUESTION_BANK) }
                                )

                                ExamHubTab.RANKINGS -> RankingsTab(
                                    currentUserId = uiState.currentUserId,
                                    rankings = dataState.rankings,
                                    onInspectOwnAttempt = viewModel::inspectOwnReview
                                )

                                ExamHubTab.QUESTION_BANK -> QuestionBankTab(
                                    uiState = uiState,
                                    ready = dataState,
                                    canManage = viewModel::canManage,
                                    onUploadPdfClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                    onSelectBatchFilter = viewModel::selectBankBatchFilter,
                                    onDeleteBatch = viewModel::deleteBatch,
                                    onToggleQuestion = viewModel::toggleQuestionActive,
                                    onDeleteQuestion = viewModel::deleteQuestion
                                )
                            }
                        }
                    }
                }

                if (uiState.isScanningPdf) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.68f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(24.dp)
                                .glassPanel(shape = RoundedCornerShape(24.dp), highlighted = true)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = SoftCyan)
                            Text(
                                text = "Scanning Q&A PDF with AI",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = uiState.scanProgressText.ifBlank { "Extracting MCQ, Fill-in-Blank, and Descriptive Q&As..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.pendingDraft?.let { draft ->
        PdfReviewSheet(
            draft = draft,
            onTitleChange = viewModel::updateDraftTitle,
            onChangeMarks = viewModel::updateDraftQuestionMarks,
            onChangeType = viewModel::updateDraftQuestionType,
            onRemoveQuestion = viewModel::removeDraftQuestion,
            onDismiss = viewModel::dismissPendingDraft,
            onConfirmSave = viewModel::confirmUploadDraft
        )
    }

    uiState.inspectedReview?.let { review ->
        PastReviewBottomSheet(
            review = review,
            onDismiss = viewModel::dismissInspectedReview
        )
    }
}

@Composable
private fun ExamHubHeader(
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .testTag("exam_back_button")
                    .minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to BS Reader",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Exam Center",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Enter Name, Factory & Phase to Attend • AI Graded Ranks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AttendExamTab(
    uiState: ExamUiState,
    ready: ExamDataState.Ready,
    onNameChange: (String) -> Unit,
    onPhaseChange: (String) -> Unit,
    onFactoryChange: (String) -> Unit,
    onSelectBatchFilter: (String?) -> Unit,
    onStartExam: () -> Unit,
    onOpenUploadTab: () -> Unit
) {
    val activeQuestions = remember(ready.questions, uiState.selectedBatchFilterId) {
        ready.questions
            .filter { it.active }
            .let { list ->
                val filterId = uiState.selectedBatchFilterId
                if (filterId != null) list.filter { it.batchId == filterId } else list
            }
    }
    val mcqCount = activeQuestions.count { it.parsedType == QuestionType.MCQ }
    val fillCount = activeQuestions.count { it.parsedType == QuestionType.FILL_IN_BLANK }
    val descCount = activeQuestions.count { it.parsedType == QuestionType.DESCRIPTIVE }
    val totalMarks = activeQuestions.sumOf { it.marks }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "exam_summary_card") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(shape = RoundedCornerShape(24.dp), highlighted = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Exam Paper",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "${ready.rankings.size} Candidates Ranked",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Text(
                    text = "${activeQuestions.size} Questions • $totalMarks Total Marks",
                    style = MaterialTheme.typography.titleMedium,
                    color = SoftCyan
                )
                Text(
                    text = "MCQ: $mcqCount  •  Fill in Blank: $fillCount  •  Written Description: $descCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )

                if (ready.batches.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all_batches") {
                            FilterChip(
                                selected = uiState.selectedBatchFilterId == null,
                                onClick = { onSelectBatchFilter(null) },
                                label = { Text("All PDFs (${ready.questions.count { it.active }})") },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color(0xFF12162E)
                                )
                            )
                        }
                        items(ready.batches, key = { it.id }) { batch ->
                            FilterChip(
                                selected = uiState.selectedBatchFilterId == batch.id,
                                onClick = { onSelectBatchFilter(batch.id) },
                                label = { Text(batch.title) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color(0xFF12162E)
                                )
                            )
                        }
                    }
                }
            }
        }

        if (activeQuestions.isEmpty()) {
            item(key = "no_questions_prompt") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(22.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = SoftCyan,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "No Questions Uploaded Yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Upload your Q&A PDF file first. The AI scanner will extract MCQs, Fill-in-the-blanks, and Descriptive Q&As automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onOpenUploadTab,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Go to Q&A PDF Bank")
                    }
                }
            }
        }

        item(key = "candidate_registration_form") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(shape = RoundedCornerShape(24.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Candidate Registration",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Enter your details below before attending. After submission, your marks and rank out of all candidates will appear immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = uiState.candidateName,
                    onValueChange = onNameChange,
                    label = { Text("Candidate Name *") },
                    placeholder = { Text("e.g. Mohidur Rahman Lalon") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoftCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("candidate_name_input")
                )

                OutlinedTextField(
                    value = uiState.phase,
                    onValueChange = onPhaseChange,
                    label = { Text("Phase *") },
                    placeholder = { Text("e.g. AQC – 10th Phase") },
                    leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricViolet,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("candidate_phase_input")
                )

                OutlinedTextField(
                    value = uiState.factoryName,
                    onValueChange = onFactoryChange,
                    label = { Text("Factory Name *") },
                    placeholder = { Text("e.g. GMS Composite Knitting Ind. Ltd.") },
                    leadingIcon = { Icon(Icons.Default.Factory, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoftPinkOrchid,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("candidate_factory_input")
                )

                Button(
                    onClick = onStartExam,
                    enabled = activeQuestions.isNotEmpty(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_exam_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Exam (${activeQuestions.size} Questions)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RankingsTab(
    currentUserId: String,
    rankings: List<RankedSubmission>,
    onInspectOwnAttempt: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "rankings_banner") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(shape = RoundedCornerShape(22.dp), highlighted = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFD56B))
                    Text(
                        text = "Candidate Standings (${rankings.size} Total)",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                }
                Text(
                    text = "Ranked by percentage score, obtained marks, and submission time. Tap your own entry to review AI feedback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        if (rankings.isEmpty()) {
            item(key = "no_rankings") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(20.dp))
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No candidates have submitted the exam yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(rankings, key = { it.submission.id }) { entry ->
                val sub = entry.submission
                val isOwn = sub.userId == currentUserId
                val rankTint = when (entry.rank) {
                    1 -> Color(0xFFFFD56B)
                    2 -> Color(0xFFDCE3F5)
                    3 -> Color(0xFFF3A775)
                    else -> SoftCyan
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(18.dp), highlighted = isOwn)
                        .clickable(enabled = isOwn) { onInspectOwnAttempt(sub.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = rankTint.copy(alpha = 0.20f),
                        border = BorderStroke(1.dp, rankTint.copy(alpha = 0.60f)),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "#${entry.rank}",
                                style = MaterialTheme.typography.titleSmall,
                                color = rankTint,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = sub.candidateName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isOwn) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SoftCyan.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = "You",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoftCyan,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${sub.phase} • ${sub.factoryName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${formatScore(sub.obtainedMarks)} / ${sub.totalMarks}",
                            style = MaterialTheme.typography.titleSmall,
                            color = SoftCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${formatScore(sub.percentage)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionBankTab(
    uiState: ExamUiState,
    ready: ExamDataState.Ready,
    canManage: (String) -> Boolean,
    onUploadPdfClick: () -> Unit,
    onSelectBatchFilter: (String?) -> Unit,
    onDeleteBatch: (ExamPdfBatch) -> Unit,
    onToggleQuestion: (ExamQuestion) -> Unit,
    onDeleteQuestion: (ExamQuestion) -> Unit
) {
    val displayedQuestions = remember(ready.questions, uiState.bankBatchFilterId) {
        val batchFilter = uiState.bankBatchFilterId
        if (batchFilter == null) ready.questions else ready.questions.filter { it.batchId == batchFilter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "upload_qa_pdf_card") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(shape = RoundedCornerShape(24.dp), highlighted = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = SoftCyan, modifier = Modifier.size(26.dp))
                    Text(
                        text = "Upload Q&A PDF",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                }
                Text(
                    text = "Select any PDF containing questions & answers. AI scans MCQs, Fill-in-the-blanks, and Written Descriptions. Upload additional PDFs anytime to append more questions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Button(
                    onClick = onUploadPdfClick,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF12162E)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("upload_qa_pdf_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (ready.batches.isEmpty()) "Select Q&A PDF File" else "Upload More Q&A PDF",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (ready.batches.isNotEmpty()) {
            item(key = "uploaded_batches_header") {
                Text(
                    text = "Uploaded PDF Sources (${ready.batches.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            items(ready.batches, key = { it.id }) { batch ->
                val isFiltered = uiState.bankBatchFilterId == batch.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(16.dp), highlighted = isFiltered)
                        .clickable { onSelectBatchFilter(if (isFiltered) null else batch.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = batch.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${batch.questionCount} Qs (MCQ ${batch.mcqCount} • Blank ${batch.fillCount} • Desc ${batch.descriptiveCount}) • ${batch.pageCount}p",
                            style = MaterialTheme.typography.bodySmall,
                            color = SoftCyan
                        )
                        Text(
                            text = "${batch.fileName} • by ${batch.uploaderName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (canManage(batch.userId)) {
                        IconButton(
                            onClick = { onDeleteBatch(batch) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete batch", tint = ErrorCoral)
                        }
                    }
                }
            }
        }

        item(key = "question_bank_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Question Bank (${displayedQuestions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (uiState.bankBatchFilterId != null) {
                    Text(
                        text = "Show All",
                        style = MaterialTheme.typography.labelMedium,
                        color = SoftCyan,
                        modifier = Modifier.clickable { onSelectBatchFilter(null) }
                    )
                }
            }
        }

        itemsIndexed(displayedQuestions, key = { _, q -> q.id }) { idx, question ->
            BankQuestionCard(
                index = idx,
                question = question,
                canManage = canManage(question.userId),
                onToggleActive = { onToggleQuestion(question) },
                onDelete = { onDeleteQuestion(question) }
            )
        }
    }
}

@Composable
private fun BankQuestionCard(
    index: Int,
    question: ExamQuestion,
    canManage: Boolean,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = when (question.parsedType) {
        QuestionType.MCQ -> SoftCyan
        QuestionType.FILL_IN_BLANK -> ElectricViolet
        QuestionType.DESCRIPTIVE -> SoftPinkOrchid
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accent.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.40f))
            ) {
                Text(
                    text = "Q${index + 1} • ${question.parsedType.label} • ${question.marks}m" + if (!question.active) " (Hidden)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (canManage) {
                Row {
                    IconButton(
                        onClick = onToggleActive,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (question.active) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (question.active) "Hide question" else "Activate question",
                            tint = if (question.active) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete question",
                            tint = ErrorCoral,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = question.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = if (question.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Key: ${question.correctAnswer}",
            style = MaterialTheme.typography.bodySmall,
            color = SoftCyan.copy(alpha = 0.88f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastReviewBottomSheet(
    review: ExamReview,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${review.candidateName} — ${formatScore(review.obtainedMarks)} / ${review.totalMarks}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${review.phase} • ${review.factoryName}",
                style = MaterialTheme.typography.bodySmall,
                color = SoftCyan
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(review.items, key = { idx, item -> "${idx}_${item.questionId}" }) { idx, item ->
                    GradedAnswerReviewCard(index = idx, item = item)
                }
            }
        }
    }
}
