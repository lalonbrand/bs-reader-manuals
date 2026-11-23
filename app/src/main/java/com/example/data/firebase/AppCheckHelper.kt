package com.example.data.firebase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
private var lastFirebaseInitError: String? = null

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
                Log.d(TAG, "App Check debug secret configured.")
            } else {
                Log.w(TAG, "No debug token found in launch Intent extras.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
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
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
