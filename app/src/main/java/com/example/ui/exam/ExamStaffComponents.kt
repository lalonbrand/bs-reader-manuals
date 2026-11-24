package com.example.ui.exam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.exam.SuperAdminEntry
import com.example.data.firebase.StaffAuthManager
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.glassPanel

@Composable
internal fun ManageSuperAdminsCard(
    currentSessionEmail: String,
    superAdmins: List<SuperAdminEntry>,
    isUpdating: Boolean,
    onGrantSuperAdmin: (String) -> Unit,
    onRevokeSuperAdmin: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var candidateInput by remember { mutableStateOf("") }
    var pendingRevokeEmail by remember { mutableStateOf<String?>(null) }

    val totalCount = 1 + superAdmins.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(20.dp), highlighted = expanded)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable { expanded = !expanded }
                .testTag("toggle_manage_super_admins"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = SoftCyan,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "Manage Super Admins ($totalCount)",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (expanded) {
                            "Add backup Super Admins or demote back to Coordinator"
                        } else {
                            "Tap to add or change Super Admin accounts"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse Super Admin manager" else "Expand Super Admin manager",
                tint = SoftCyan
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

                // Permanent Root Super Admin row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = StaffAuthManager.SUPER_ADMIN_EMAIL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Permanent backup owner",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftCyan
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SoftCyan.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, SoftCyan.copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = "Root",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Delegated Super Admins
                superAdmins.forEach { entry ->
                    val email = entry.email.ifBlank { entry.id }
                    val isSelf = email.equals(currentSessionEmail, ignoreCase = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = email + if (isSelf) " (You)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            if (entry.addedByEmail.isNotBlank()) {
                                Text(
                                    text = "Granted by ${entry.addedByEmail}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.68f)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { pendingRevokeEmail = email },
                            enabled = !isUpdating,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("revoke_super_admin_$email")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonRemove,
                                contentDescription = "Demote $email to Coordinator",
                                tint = ErrorCoral,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Demote", color = ErrorCoral, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

                OutlinedTextField(
                    value = candidateInput,
                    onValueChange = { candidateInput = it },
                    label = { Text("Staff Username or Email") },
                    placeholder = { Text("e.g. name@gmail.com or coord1") },
                    leadingIcon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
                    singleLine = true,
                    enabled = !isUpdating,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_super_admin_email_input")
                )

                Button(
                    onClick = {
                        val trimmed = candidateInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onGrantSuperAdmin(trimmed)
                            candidateInput = ""
                        }
                    },
                    enabled = !isUpdating && candidateInput.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("grant_super_admin_button")
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Updating Role...")
                    } else {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Super Admin Access", fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(
                    text = "Tip: Make sure the user account is added in Firebase Console → Authentication → Users. Plain usernames resolve to @bsreader.local; full Gmail/Outlook/Yahoo emails work directly.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }
    }

    pendingRevokeEmail?.let { targetEmail ->
        val isSelf = targetEmail.equals(currentSessionEmail, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { pendingRevokeEmail = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("Demote Super Admin?") },
            text = {
                Text(
                    if (isSelf) {
                        "Removing your own Super Admin access ($targetEmail) will switch you back to Coordinator (Upload Only) immediately."
                    } else {
                        "Change $targetEmail back to Coordinator (Upload Only)? They can still upload Q&A PDFs unless blocked."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeSuperAdmin(targetEmail)
                        pendingRevokeEmail = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorCoral)
                ) {
                    Text("Demote")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevokeEmail = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
internal fun StaffSignInDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text("Exam Staff Sign In") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sign in with your Username or Email & Password.\n• Super Admin: Full control (questions, rankings & roles)\n• Coordinator: Upload Q&A PDF only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username or Email") },
                    placeholder = { Text("e.g. mrlalon or name@gmail.com") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("staff_username_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("staff_password_input")
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCoral
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSignIn(username, password) },
                enabled = !isLoading,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag("staff_signin_submit_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Signing In...")
                } else {
                    Text("Sign In")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}
