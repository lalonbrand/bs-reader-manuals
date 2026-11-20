package com.example.data.firebase

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

sealed interface GoogleSignInOutcome {
    data object Success : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data object NoSavedCredential : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"

    suspend fun attemptSilentSignIn(activityContext: Context): GoogleSignInOutcome {
        return try {
            val serverClientId = activityContext.getString(R.string.default_web_client_id)
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(true)
                .build()
            executeCredentialFlow(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (e: Exception) {
            Log.d(TAG, "Silent Google Sign-In skipped: ${e.message}")
            GoogleSignInOutcome.NoSavedCredential
        }
    }

    suspend fun signInInteractive(activityContext: Context): GoogleSignInOutcome {
        val serverClientId = activityContext.getString(R.string.default_web_client_id)
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return executeCredentialFlow(activityContext, request)
    }

    suspend fun signOut(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing credential state on sign-out", e)
        }
    }

    private suspend fun executeCredentialFlow(
        activityContext: Context,
        request: GetCredentialRequest
    ): GoogleSignInOutcome {
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
            )
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdCredential.idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).awaitTask()
                GoogleSignInOutcome.Success
            } else {
                GoogleSignInOutcome.Error("Unsupported credential type returned.")
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInOutcome.Cancelled
        } catch (_: NoCredentialException) {
            GoogleSignInOutcome.NoSavedCredential
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager sign-in failed", e)
            GoogleSignInOutcome.Error(e.errorMessage?.toString() ?: e.localizedMessage ?: "Google Sign-In failed")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase credential sign-in failed", e)
            GoogleSignInOutcome.Error(e.localizedMessage ?: "Authentication failed")
        }
    }
}
