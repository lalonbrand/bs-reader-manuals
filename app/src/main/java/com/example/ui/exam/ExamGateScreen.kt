package com.example.ui.exam

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.firebase.GoogleAuthManager
import com.example.data.firebase.GoogleSignInOutcome
import com.example.data.firebase.authStateFlow
import com.example.ui.theme.CosmicGlassBackdrop
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.SoftPinkOrchid
import com.example.ui.theme.glassPanel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExamGateScreen(
    onNavigateBack: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val context = LocalContext.current
    val application = context.applicationContext as Application

    val user = currentUser
    if (user == null) {
        ExamAuthScreen(
            onNavigateBack = onNavigateBack
        )
    } else {
        key(user.uid) {
            val viewModel: ExamViewModel = viewModel(
                key = user.uid,
                factory = ExamViewModel.Factory(
                    application = application,
                    currentUserId = user.uid,
                    uploaderName = user.displayName?.takeIf { it.isNotBlank() }
                        ?: user.email?.substringBefore('@')
                        ?: "Candidate",
                    userEmail = user.email
                )
            )
            val scope = rememberCoroutineScope()
            ExamCenterRoot(
                viewModel = viewModel,
                userDisplayName = user.displayName ?: user.email ?: "Signed In",
                onNavigateBack = onNavigateBack,
                onSignOut = {
                    scope.launch {
                        withContext(NonCancellable) {
                            GoogleAuthManager.signOut(context)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ExamAuthScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSigningIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            isSigningIn = true
            when (val outcome = GoogleAuthManager.attemptSilentSignIn(context)) {
                is GoogleSignInOutcome.Error -> authError = outcome.message
                else -> Unit
            }
            isSigningIn = false
        }
    }

    CosmicGlassBackdrop {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .testTag("exam_auth_back_button")
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to bookshelf",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Exam Center",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(28.dp), highlighted = true)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AssignmentTurnedIn,
                            contentDescription = null,
                            tint = SoftCyan,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "Sign in to Attend Exams",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Connect your Google account to sync shared Q&A PDF question banks, grade descriptive answers with AI, and calculate your rank among all candidates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuthFeatureRow(
                            icon = Icons.Default.PictureAsPdf,
                            tint = SoftCyan,
                            text = "Upload Q&A PDFs & append new questions anytime"
                        )
                        AuthFeatureRow(
                            icon = Icons.Default.AutoAwesome,
                            tint = ElectricViolet,
                            text = "MCQ, Fill-in-Blank & AI-evaluated Written Answers"
                        )
                        AuthFeatureRow(
                            icon = Icons.Default.Leaderboard,
                            tint = SoftPinkOrchid,
                            text = "Instant marks & live rank out of total candidates"
                        )
                    }

                    authError?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ErrorCoral.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isSigningIn) return@Button
                            scope.launch {
                                isSigningIn = true
                                authError = null
                                when (val outcome = GoogleAuthManager.signInInteractive(context)) {
                                    is GoogleSignInOutcome.Error -> authError = outcome.message
                                    is GoogleSignInOutcome.NoSavedCredential ->
                                        authError = "No Google account found on this device. Add a Google account in device settings and try again."
                                    is GoogleSignInOutcome.Cancelled -> Unit
                                    is GoogleSignInOutcome.Success -> Unit
                                }
                                isSigningIn = false
                            }
                        },
                        enabled = !isSigningIn,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF12162E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF12162E)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Signing in...", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = SoftCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Secured by Firebase Auth & App Check",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AuthFeatureRow(
    icon: ImageVector,
    tint: Color,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}
