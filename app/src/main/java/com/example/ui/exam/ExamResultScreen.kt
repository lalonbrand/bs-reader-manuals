package com.example.ui.exam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.exam.GradedAnswerItem
import com.example.data.exam.QuestionType
import com.example.data.exam.RankedSubmission
import com.example.ui.theme.CosmicGlassBackdrop
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.SoftPinkOrchid
import com.example.ui.theme.glassPanel

@Composable
fun ExamResultScreen(
    result: ExamResultView,
    rankings: List<RankedSubmission>,
    onDone: () -> Unit,
    onViewLeaderboard: () -> Unit
) {
    BackHandler { onDone() }

    val matchingRank = rankings.firstOrNull { it.submission.id == result.submissionId }
    val rankNumber = matchingRank?.rank ?: 1
    val totalCandidates = matchingRank?.totalCandidates ?: maxOf(rankings.size, 1)

    CosmicGlassBackdrop {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .widthIn(max = 880.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        IconButton(
                            onClick = onDone,
                            modifier = Modifier
                                .testTag("result_back_button")
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Exam Center",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Exam Result & Rank",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${result.candidateName} • ${result.phase} • ${result.factoryName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "hero_score_card") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(shape = RoundedCornerShape(26.dp), highlighted = true)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "YOUR RANK",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoftCyan
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.EmojiEvents,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD56B),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Text(
                                            text = "#$rankNumber of $totalCandidates",
                                            style = MaterialTheme.typography.displaySmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = if (totalCandidates == 1) "First candidate recorded" else "Ranked among $totalCandidates total candidates",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.80f)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = Color.White.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f))
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = "${formatScore(result.obtainedMarks)} / ${result.totalMarks}",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${formatScore(result.percentage)}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SoftCyan
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (result.mcqTotal > 0) {
                                    BreakdownPill(
                                        label = "MCQ",
                                        value = "${result.mcqCorrect}/${result.mcqTotal}",
                                        accent = SoftCyan,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (result.fillTotalMarks > 0) {
                                    BreakdownPill(
                                        label = "Fill Blank",
                                        value = "${formatScore(result.fillMarks)}/${result.fillTotalMarks}m",
                                        accent = ElectricViolet,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (result.descTotalMarks > 0) {
                                    BreakdownPill(
                                        label = "Description",
                                        value = "${formatScore(result.descMarks)}/${result.descTotalMarks}m",
                                        accent = SoftPinkOrchid,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onViewLeaderboard,
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFF12162E)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("view_full_leaderboard_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Leaderboard, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("View Rankings")
                                }

                                FilledTonalButton(
                                    onClick = onDone,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Text("Exam Hub")
                                }
                            }
                        }
                    }

                    item(key = "breakdown_header") {
                        Text(
                            text = "Answer Breakdown & AI Evaluation (${result.gradedItems.size} Questions)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    itemsIndexed(result.gradedItems, key = { idx, item -> "${idx}_${item.questionId}" }) { idx, item ->
                        GradedAnswerReviewCard(index = idx, item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = accent)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GradedAnswerReviewCard(
    index: Int,
    item: GradedAnswerItem
) {
    val qType = QuestionType.fromWire(item.questionType)
    val earnedRatio = if (item.maxMarks > 0) item.awardedMarks / item.maxMarks.toDouble() else 0.0
    val scoreColor = when {
        earnedRatio >= 0.99 -> SoftCyan
        earnedRatio > 0.0 -> Color(0xFFFFD56B)
        else -> ErrorCoral
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Q${index + 1} • ${qType.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = scoreColor.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "${formatScore(item.awardedMarks)} / ${item.maxMarks} m",
                    style = MaterialTheme.typography.labelMedium,
                    color = scoreColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Text(
            text = item.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Your Answer: ${item.userAnswer}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "PDF Reference: ${item.referenceAnswer}",
                style = MaterialTheme.typography.bodySmall,
                color = SoftCyan.copy(alpha = 0.90f)
            )
        }

        if (item.feedback.isNotBlank()) {
            Text(
                text = "Evaluator Note: ${item.feedback}",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricViolet
            )
        }
    }
}

fun formatScore(value: Double): String {
    val roundedInt = value.toInt()
    return if (kotlin.math.abs(value - roundedInt) < 0.05) {
        roundedInt.toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
}
