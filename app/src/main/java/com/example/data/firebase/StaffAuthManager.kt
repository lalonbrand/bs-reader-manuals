package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser

enum class ExamStaffRole(val label: String) {
    NONE("Guest"),
    COORDINATOR("Coordinator"),
    SUPER_ADMIN("Super Admin")
}

data class ExamStaffSession(
    val uid: String = "",
    val email: String = "",
    val role: ExamStaffRole = ExamStaffRole.NONE,
    val isRevoked: Boolean = false
) {
    val isSignedIn: Boolean
        get() = uid.isNotBlank() && role != ExamStaffRole.NONE

    val isSuperAdmin: Boolean
        get() = role == ExamStaffRole.SUPER_ADMIN

    val isRootSuperAdmin: Boolean
        get() = StaffAuthManager.isRootSuperAdminEmail(email)

    val canUploadPdf: Boolean
        get() = isSignedIn && (!isRevoked || isSuperAdmin)

    val canManageBank: Boolean
        get() = isSuperAdmin
}

sealed interface StaffSignInOutcome {
    data class Success(val session: ExamStaffSession) : StaffSignInOutcome
    data class Error(val message: String) : StaffSignInOutcome
}

object StaffAuthManager {
    private const val TAG = "StaffAuthManager"
    const val SUPER_ADMIN_EMAIL = "mrlalon@bsreader.local"
    const val DEFAULT_STAFF_DOMAIN = "bsreader.local"
    private val STAFF_EMAIL_REGEX = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z0-9]{2,}$")

    fun normalizeUsernameOrEmail(rawInput: String): String {
        val cleaned = rawInput.trim().lowercase()
        if (cleaned.isEmpty()) return ""
        return if ('@' in cleaned) cleaned else "$cleaned@$DEFAULT_STAFF_DOMAIN"
    }

    fun isValidStaffEmail(normalizedEmail: String): Boolean {
        val trimmed = normalizedEmail.trim().lowercase()
        return trimmed.length in 3..160 && STAFF_EMAIL_REGEX.matches(trimmed)
    }

    fun isRootSuperAdminEmail(email: String?): Boolean {
        return email?.trim()?.lowercase() == SUPER_ADMIN_EMAIL
    }

    fun roleForEmail(email: String?, isDelegatedSuperAdmin: Boolean = false): ExamStaffRole {
        val normalized = email?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return ExamStaffRole.NONE
        return if (normalized == SUPER_ADMIN_EMAIL || isDelegatedSuperAdmin) {
            ExamStaffRole.SUPER_ADMIN
        } else {
            ExamStaffRole.COORDINATOR
        }
    }

    fun sessionFromUser(
        user: FirebaseUser?,
        isDelegatedSuperAdmin: Boolean = false,
        isRevoked: Boolean = false
    ): ExamStaffSession {
        if (user == null) return ExamStaffSession()
        val email = user.email.orEmpty().trim().lowercase()
        val role = roleForEmail(email, isDelegatedSuperAdmin)
        return ExamStaffSession(
            uid = user.uid,
            email = email,
            role = role,
            isRevoked = isRevoked && role != ExamStaffRole.SUPER_ADMIN
        )
    }

    suspend fun signInWithUsernamePassword(
        appContext: Context,
        usernameOrEmail: String,
        password: String
    ): StaffSignInOutcome {
        if (!ensureFirebaseInitialized(appContext)) {
            return StaffSignInOutcome.Error(getFirebaseInitDiagnosticMessage(appContext))
        }

        val normalizedEmail = normalizeUsernameOrEmail(usernameOrEmail)
        if (normalizedEmail.isEmpty()) {
            return StaffSignInOutcome.Error("Please enter your username or email.")
        }
        if (password.isBlank()) {
            return StaffSignInOutcome.Error("Please enter your password.")
        }

        return try {
            val result = FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(normalizedEmail, password)
                .awaitTask()
            val user = result.user
            if (user == null) {
                StaffSignInOutcome.Error("Sign-in succeeded without a user session. Please retry.")
            } else {
                StaffSignInOutcome.Success(sessionFromUser(user))
            }
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "Staff sign-in failed (${e.errorCode})", e)
            val friendly = when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED", "OPERATION_NOT_ALLOWED" ->
                    "Email/Password sign-in is not enabled yet in Firebase Console (Authentication → Sign-in method → Email/Password)."
                "ERROR_USER_NOT_FOUND", "USER_NOT_FOUND",
                "ERROR_WRONG_PASSWORD", "WRONG_PASSWORD",
                "ERROR_INVALID_CREDENTIAL", "INVALID_LOGIN_CREDENTIALS" ->
                    "Invalid username or password for $normalizedEmail."
                "ERROR_INVALID_EMAIL", "INVALID_EMAIL" ->
                    "Invalid username format. Enter your username (e.g. mrlalon) or full email."
                "ERROR_USER_DISABLED" ->
                    "This staff account has been disabled."
                "ERROR_TOO_MANY_REQUESTS" ->
                    "Too many failed attempts. Please wait a moment and try again."
                else -> e.localizedMessage ?: "Sign-in failed (${e.errorCode})."
            }
            StaffSignInOutcome.Error(friendly)
        } catch (e: Exception) {
            Log.e(TAG, "Staff sign-in error", e)
            val rawMsg = e.localizedMessage.orEmpty()
            val friendly = if (rawMsg.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ||
                rawMsg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true)
            ) {
                "Enable Email/Password sign-in in Firebase Console → Authentication → Sign-in method, and add staff users first."
            } else {
                rawMsg.ifBlank { "Unable to sign in. Check internet connection and retry." }
            }
            StaffSignInOutcome.Error(friendly)
        }
    }

    fun signOut() {
        runCatching { FirebaseAuth.getInstance().signOut() }
    }
}
