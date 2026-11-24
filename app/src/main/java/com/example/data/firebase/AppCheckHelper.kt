package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

private const val TAG = "FirebaseInit"

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
