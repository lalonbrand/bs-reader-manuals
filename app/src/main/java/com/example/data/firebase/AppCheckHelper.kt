package com.example.data.firebase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
private var lastFirebaseInitError: String? = null

@Volatile
private var appCheckProviderSummary: String = "AppCheck not configured"

@Volatile
private var appCheckInitError: String? = null

fun ensureFirebaseInitialized(context: Context): Boolean {
    val appContext = context.applicationContext ?: context
    return try {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            lastFirebaseInitError = null
            true
        } else {
            val initialized = FirebaseApp.initializeApp(appContext)
            if (initialized == null) {
                val appIdRes = appContext.resources.getIdentifier(
                    "google_app_id",
                    "string",
                    appContext.packageName
                )
                lastFirebaseInitError = if (appIdRes == 0) {
                    "This APK was built without app/google-services.json (missing google_app_id). Download the APK directly from AI Studio, or add GOOGLE_SERVICES_JSON in GitHub Repository Secrets and re-run Build BS Reader APK."
                } else {
                    "FirebaseApp.initializeApp returned null despite google_app_id resource existing."
                }
                Log.e(TAG, lastFirebaseInitError!!)
                false
            } else {
                lastFirebaseInitError = null
                true
            }
        }
    } catch (e: Exception) {
        lastFirebaseInitError = e.localizedMessage ?: "Failed to initialize FirebaseApp."
        Log.e(TAG, "Failed to initialize FirebaseApp", e)
        false
    }
}

fun getFirebaseInitDiagnosticMessage(context: Context): String {
    val appContext = context.applicationContext ?: context
    lastFirebaseInitError?.let { return it }
    val appIdRes = appContext.resources.getIdentifier("google_app_id", "string", appContext.packageName)
    return if (appIdRes == 0) {
        "This APK was built without app/google-services.json (missing google_app_id). Download the APK directly from AI Studio, or add GOOGLE_SERVICES_JSON in GitHub Repository Secrets and re-run Build BS Reader APK."
    } else {
        "Firebase is not initialized in this APK build."
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    try {
        if (!ensureFirebaseInitialized(appContext)) {
            appCheckProviderSummary = "Firebase not initialized"
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit() ensures DebugAppCheckProviderFactory reads the secret immediately.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                appCheckProviderSummary = "DebugProvider (debug token provided)"
                Log.d(TAG, "App Check debug secret configured.")
            } else {
                appCheckProviderSummary = "DebugProvider (no AI Studio debug token)"
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            appCheckInitError = null
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = appContext.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                appContext.packageName
            )
            if (resId != 0) {
                val siteKey = appContext.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    appCheckInitError = null
                    appCheckProviderSummary = "reCAPTCHA Enterprise (${siteKey.take(8)}...)"
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    appCheckInitError = "recaptcha_site_key resource string is blank."
                    appCheckProviderSummary = "Missing recaptcha_site_key"
                    Log.e(TAG, appCheckInitError!!)
                }
            } else {
                appCheckInitError = "recaptcha_site_key string resource not found in package."
                appCheckProviderSummary = "Missing recaptcha_site_key"
                Log.e(TAG, appCheckInitError!!)
            }
        }
    } catch (e: Exception) {
        appCheckInitError = e.localizedMessage ?: e.javaClass.simpleName
        appCheckProviderSummary = "Init failed: $appCheckInitError"
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

suspend fun diagnoseAiFailure(context: Context?, error: Throwable): String {
    val rawMessage = error.localizedMessage?.trim().orEmpty().ifBlank { "AI request failed." }
    val chainText = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" | ")
        .lowercase()

    val isAppCheckFailure = chainText.contains("app check") ||
        chainText.contains("appcheck") ||
        chainText.contains("attestation") ||
        chainText.contains("403")

    if (!isAppCheckFailure) {
        return rawMessage
    }

    val appContext = context?.applicationContext
        ?: runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()

    val buildLabel = if (BuildConfig.DEBUG) {
        "Debug APK v${BuildConfig.VERSION_NAME}"
    } else {
        "Release APK v${BuildConfig.VERSION_NAME}"
    }

    if (BuildConfig.DEBUG) {
        return "$rawMessage\n\n[$buildLabel • $appCheckProviderSummary] Push the latest code to GitHub, wait for Build BS Reader APK to finish, and install BS-Reader.apk from the Release showing 'App Check (reCAPTCHA Release): ENABLED'."
    }

    if (appCheckInitError != null) {
        return "$rawMessage\n\n[$buildLabel • $appCheckInitError] Ensure app/src/main/res/values/firebase_applet_config.xml is pushed to GitHub and rebuild."
    }

    if (appContext != null && !ensureFirebaseInitialized(appContext)) {
        return "$rawMessage\n\n[${getFirebaseInitDiagnosticMessage(appContext)}]"
    }

    val tokenCheck = withTimeoutOrNull(4_500L) {
        probeAppCheckToken()
    }

    return when {
        tokenCheck == null ->
            "$rawMessage\n\n[$buildLabel • $appCheckProviderSummary] App Check token fetch timed out. Check mobile data/Wi-Fi and disable VPN or Private DNS."
        tokenCheck.isFailure -> {
            val probeErr = tokenCheck.exceptionOrNull()?.localizedMessage ?: "unknown token error"
            "$rawMessage\n\n[$buildLabel • $appCheckProviderSummary] Token error: $probeErr"
        }
        else ->
            "$rawMessage\n\n[$buildLabel • $appCheckProviderSummary] Ensure phone Date & Time is set to Automatic and Google Play Services is updated."
    }
}

private suspend fun probeAppCheckToken(): Result<String> = suspendCancellableCoroutine { cont ->
    try {
        FirebaseAppCheck.getInstance()
            .getAppCheckToken(false)
            .addOnSuccessListener { token ->
                if (cont.isActive) {
                    cont.resume(Result.success(token.token))
                }
            }
            .addOnFailureListener { exc ->
                if (cont.isActive) {
                    cont.resume(Result.failure(exc))
                }
            }
    } catch (e: Exception) {
        if (cont.isActive) {
            cont.resume(Result.failure(e))
        }
    }
}
