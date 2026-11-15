package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.folioSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "folio_settings")

enum class ReaderSurfaceTone(val label: String) {
    PAPER("Vellum"),
    SEPIA("Sepia"),
    NIGHT("Night")
}

const val DEFAULT_CATALOG_URL = "https://lalonbrand.github.io/bs-reader-manuals/catalog.json"

data class ReaderPreferences(
    val catalogUrl: String = DEFAULT_CATALOG_URL,
    val surfaceTone: ReaderSurfaceTone = ReaderSurfaceTone.PAPER,
    val showPageOverlay: Boolean = true
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val CATALOG_URL = stringPreferencesKey("catalog_url")
        val SURFACE_TONE = stringPreferencesKey("surface_tone")
        val SHOW_PAGE_OVERLAY = booleanPreferencesKey("show_page_overlay")
    }

    val preferences: Flow<ReaderPreferences> = context.folioSettingsStore.data.map { prefs ->
        val tone = prefs[Keys.SURFACE_TONE]?.let { raw ->
            runCatching { ReaderSurfaceTone.valueOf(raw) }.getOrNull()
        } ?: ReaderSurfaceTone.PAPER
        ReaderPreferences(
            catalogUrl = prefs[Keys.CATALOG_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_CATALOG_URL,
            surfaceTone = tone,
            showPageOverlay = prefs[Keys.SHOW_PAGE_OVERLAY] ?: true
        )
    }

    suspend fun setCatalogUrl(url: String) {
        context.folioSettingsStore.edit { it[Keys.CATALOG_URL] = url.trim() }
    }

    suspend fun setSurfaceTone(tone: ReaderSurfaceTone) {
        context.folioSettingsStore.edit { it[Keys.SURFACE_TONE] = tone.name }
    }

    suspend fun setShowPageOverlay(show: Boolean) {
        context.folioSettingsStore.edit { it[Keys.SHOW_PAGE_OVERLAY] = show }
    }
}
