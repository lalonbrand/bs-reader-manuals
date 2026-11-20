package com.example.ui.exam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.exam.DraftExamQuestion
import com.example.data.exam.QuestionType
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.SoftPinkOrchid
import com.example.ui.theme.glassPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReviewSheet(
    draft: PendingPdfUploadDraft,
    onTitleChange: (String) -> Unit,
    onChangeMarks: (String, Int) -> Unit,
    onChangeType: (String, QuestionType) -> Unit,
    onRemoveQuestion: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!draft.isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Review Extracted Q&A",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${draft.fileName} • ${draft.pageCount} pages • ${draft.questions.size} questions ready to append",
                style = MaterialTheme.typography.bodySmall,
                color = SoftCyan
            )

            OutlinedTextField(
                value = draft.title,
                onValueChange = onTitleChange,
                label = { Text("PDF Set Title") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SoftCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.24f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pdf_batch_title_input")
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(draft.questions, key = { _, q -> q.tempId }) { idx, q ->
                    DraftQuestionCard(
                        index = idx,
                        question = q,
                        onChangeMarks = { newMarks -> onChangeMarks(q.tempId, newMarks) },
                        onChangeType = { newType -> onChangeType(q.tempId, newType) },
                        onRemove = { onRemoveQuestion(q.tempId) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !draft.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = onConfirmSave,
                    enabled = !draft.isSaving && draft.questions.isNotEmpty(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(2f)
                        .testTag("confirm_save_pdf_questions")
                        .minimumInteractiveComponentSize()
                ) {
                    if (draft.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving...")
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add ${draft.questions.size} Questions")
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftQuestionCard(
    index: Int,
    question: DraftExamQuestion,
    onChangeMarks: (Int) -> Unit,
    onChangeType: (QuestionType) -> Unit,
    onRemove: () -> Unit
) {
    val badgeColor = when (question.type) {
        QuestionType.MCQ -> SoftCyan
        QuestionType.FILL_IN_BLANK -> ElectricViolet
        QuestionType.DESCRIPTIVE -> SoftPinkOrchid
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "Q${index + 1} • ${question.type.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onChangeMarks(question.marks - 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease marks", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "${question.marks}m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(
                    onClick = { onChangeMarks(question.marks + 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase marks", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove question", tint = ErrorCoral, modifier = Modifier.size(18.dp))
                }
            }
        }

        Text(
            text = question.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (question.type == QuestionType.MCQ && question.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                question.options.forEachIndexed { optIdx, opt ->
                    val isRight = optIdx == question.correctOptionIndex
                    Text(
                        text = "${'A' + optIdx}. $opt" + if (isRight) "  ✓" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRight) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isRight) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        } else {
            Text(
                text = "Ref Answer: ${question.correctAnswer}",
                style = MaterialTheme.typography.bodySmall,
                color = SoftCyan.copy(alpha = 0.90f),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuestionType.entries.forEach { t ->
                FilterChip(
                    selected = question.type == t,
                    onClick = { onChangeType(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = badgeColor.copy(alpha = 0.28f),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
    }
}
