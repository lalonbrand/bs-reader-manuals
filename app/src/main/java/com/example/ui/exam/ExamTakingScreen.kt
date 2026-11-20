package com.example.ui.exam

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.exam.QuestionType
import com.example.ui.theme.CosmicGlassBackdrop
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.SoftPinkOrchid
import com.example.ui.theme.glassPanel

@Composable
fun ExamTakingScreen(
    session: ActiveExamSession,
    onSelectMcq: (String, Int) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onJumpTo: (Int) -> Unit,
    onSubmitExam: () -> Unit,
    onExitSession: () -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var showSubmitConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = !session.isSubmitting) {
        showExitConfirm = true
    }

    val currentQuestion = session.questions.getOrNull(session.currentIndex) ?: return
    val answeredCount = remember(session.questions, session.mcqSelections, session.textAnswers) {
        session.questions.count { q ->
            when (q.parsedType) {
                QuestionType.MCQ -> session.mcqSelections.containsKey(q.id)
                QuestionType.FILL_IN_BLANK, QuestionType.DESCRIPTIVE ->
                    session.textAnswers[q.id]?.isNotBlank() == true
            }
        }
    }
    val typeAccent = when (currentQuestion.parsedType) {
        QuestionType.MCQ -> SoftCyan
        QuestionType.FILL_IN_BLANK -> ElectricViolet
        QuestionType.DESCRIPTIVE -> SoftPinkOrchid
    }

    CosmicGlassBackdrop {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
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
                        .widthIn(max = 820.dp)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                            ) {
                                IconButton(
                                    onClick = { if (!session.isSubmitting) showExitConfirm = true },
                                    modifier = Modifier
                                        .testTag("exam_session_exit_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Exit exam",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = session.candidateName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${session.phase} • ${session.factoryName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = SoftCyan.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, SoftCyan.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "$answeredCount / ${session.questions.size} answered",
                                style = MaterialTheme.typography.labelMedium,
                                color = SoftCyan,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (session.currentIndex + 1).toFloat() / session.questions.size.toFloat() },
                        color = SoftCyan,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )

                    // Question number quick-jump strip
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        itemsIndexed(session.questions, key = { _, q -> q.id }) { idx, q ->
                            val isSelected = idx == session.currentIndex
                            val isAnswered = when (q.parsedType) {
                                QuestionType.MCQ -> session.mcqSelections.containsKey(q.id)
                                else -> session.textAnswers[q.id]?.isNotBlank() == true
                            }
                            Surface(
                                shape = CircleShape,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isAnswered -> SoftCyan.copy(alpha = 0.22f)
                                    else -> Color.White.copy(alpha = 0.08f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isSelected -> Color.Transparent
                                        isAnswered -> SoftCyan.copy(alpha = 0.55f)
                                        else -> Color.White.copy(alpha = 0.18f)
                                    }
                                ),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(enabled = !session.isSubmitting) { onJumpTo(idx) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            isAnswered -> SoftCyan
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Question body card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .glassPanel(shape = RoundedCornerShape(24.dp))
                            .padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = typeAccent.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, typeAccent.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = "Question ${session.currentIndex + 1} • ${currentQuestion.parsedType.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = typeAccent,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = "${currentQuestion.marks} ${if (currentQuestion.marks == 1L) "Mark" else "Marks"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Text(
                            text = currentQuestion.prompt,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Source: ${currentQuestion.batchTitle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        when (currentQuestion.parsedType) {
                            QuestionType.MCQ -> {
                                val selectedIdx = session.mcqSelections[currentQuestion.id] ?: -1
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    currentQuestion.options.forEachIndexed { optIdx, optText ->
                                        val isChosen = optIdx == selectedIdx
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (isChosen) {
                                                SoftCyan.copy(alpha = 0.20f)
                                            } else {
                                                Color.White.copy(alpha = 0.06f)
                                            },
                                            border = BorderStroke(
                                                1.2.dp,
                                                if (isChosen) SoftCyan else Color.White.copy(alpha = 0.18f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("mcq_option_$optIdx")
                                                .minimumInteractiveComponentSize()
                                                .clickable(enabled = !session.isSubmitting) {
                                                    onSelectMcq(currentQuestion.id, optIdx)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isChosen) {
                                                        Icons.Default.RadioButtonChecked
                                                    } else {
                                                        Icons.Default.RadioButtonUnchecked
                                                    },
                                                    contentDescription = null,
                                                    tint = if (isChosen) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "${'A' + optIdx}.  $optText",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            QuestionType.FILL_IN_BLANK -> {
                                val answerText = session.textAnswers[currentQuestion.id].orEmpty()
                                OutlinedTextField(
                                    value = answerText,
                                    onValueChange = { onUpdateText(currentQuestion.id, it) },
                                    label = { Text("Enter missing word or value") },
                                    placeholder = { Text("Type your answer for the blank...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White.copy(alpha = 0.09f),
                                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                        focusedBorderColor = ElectricViolet,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("fill_blank_input")
                                )
                            }

                            QuestionType.DESCRIPTIVE -> {
                                val answerText = session.textAnswers[currentQuestion.id].orEmpty()
                                OutlinedTextField(
                                    value = answerText,
                                    onValueChange = { onUpdateText(currentQuestion.id, it) },
                                    label = { Text("Write detailed answer (graded by AI against reference)") },
                                    placeholder = { Text("Explain clearly with key inspection/process points...") },
                                    minLines = 6,
                                    maxLines = 12,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White.copy(alpha = 0.09f),
                                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                        focusedBorderColor = SoftPinkOrchid,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("descriptive_answer_input")
                                )
                                Text(
                                    text = "${answerText.length} characters • AI evaluates key concepts against PDF reference answer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Bottom nav controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onJumpTo(session.currentIndex - 1) },
                            enabled = session.currentIndex > 0 && !session.isSubmitting,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("exam_prev_question_button")
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Previous")
                        }

                        if (session.currentIndex < session.questions.lastIndex) {
                            Button(
                                onClick = { onJumpTo(session.currentIndex + 1) },
                                enabled = !session.isSubmitting,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("exam_next_question_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Text("Next")
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Button(
                                onClick = { showSubmitConfirm = true },
                                enabled = !session.isSubmitting,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoftCyan,
                                    contentColor = Color(0xFF0A1329)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("exam_submit_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Submit Exam", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    if (session.currentIndex < session.questions.lastIndex) {
                        TextButton(
                            onClick = { showSubmitConfirm = true },
                            enabled = !session.isSubmitting,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .testTag("exam_early_submit_button")
                        ) {
                            Text("Finish & Submit Now ($answeredCount/${session.questions.size})")
                        }
                    }
                }

                if (session.isSubmitting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
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
                                text = "AI Grading in Progress...",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Checking MCQs, fill-in-the-blanks, and comparing written descriptions against Q&A PDF reference answers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSubmitConfirm) {
        val unanswered = session.questions.size - answeredCount
        AlertDialog(
            onDismissRequest = { showSubmitConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("Submit Exam?") },
            text = {
                Text(
                    if (unanswered > 0) {
                        "You have answered $answeredCount of ${session.questions.size} questions ($unanswered unanswered). Your descriptive answers will be graded automatically by AI and your rank calculated."
                    } else {
                        "All ${session.questions.size} questions answered! Submit now for AI grading and candidate rank calculation?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSubmitConfirm = false
                        onSubmitExam()
                    }
                ) {
                    Text("Submit & Grade")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubmitConfirm = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("Leave Current Exam?") },
            text = { Text("Your current unsubmitted answers will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        onExitSession()
                    }
                ) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("Resume")
                }
            }
        )
    }
}
