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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.folioSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "folio_settings")

enum class ReaderSurfaceTone(val label: String) {
    PAPER("Vellum"),
    SEPIA("Sepia"),
    NIGHT("Night")
}

@Serializable
data class DocumentNotification(
    val id: String,
    val bookId: String,
    val pdfFileName: String,
    val title: String,
    val sourceLabel: String,
    val timestampMillis: Long,
    val isUnread: Boolean = true
)

const val DEFAULT_CATALOG_URL = "https://lalonbrand.github.io/bs-reader-manuals/catalog.json"

data class ReaderPreferences(
    val catalogUrl: String = DEFAULT_CATALOG_URL,
    val surfaceTone: ReaderSurfaceTone = ReaderSurfaceTone.PAPER,
    val showPageOverlay: Boolean = true,
    val pageSoundEnabled: Boolean = true,
    val banglaOnlyMode: Boolean = true
)

class SettingsDataStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val CATALOG_URL = stringPreferencesKey("catalog_url")
        val SURFACE_TONE = stringPreferencesKey("surface_tone")
        val SHOW_PAGE_OVERLAY = booleanPreferencesKey("show_page_overlay")
        val PAGE_SOUND_ENABLED = booleanPreferencesKey("page_sound_enabled")
        val BANGLA_ONLY_MODE = booleanPreferencesKey("bangla_only_mode")
        val NOTIFICATIONS_JSON = stringPreferencesKey("notifications_json")
        val KNOWN_CATALOG_IDS_JSON = stringPreferencesKey("known_catalog_ids_json")
    }

    val preferences: Flow<ReaderPreferences> = context.folioSettingsStore.data.map { prefs ->
        val tone = prefs[Keys.SURFACE_TONE]?.let { raw ->
            runCatching { ReaderSurfaceTone.valueOf(raw) }.getOrNull()
        } ?: ReaderSurfaceTone.PAPER
        ReaderPreferences(
            catalogUrl = prefs[Keys.CATALOG_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_CATALOG_URL,
            surfaceTone = tone,
            showPageOverlay = prefs[Keys.SHOW_PAGE_OVERLAY] ?: true,
            pageSoundEnabled = prefs[Keys.PAGE_SOUND_ENABLED] ?: true,
            banglaOnlyMode = prefs[Keys.BANGLA_ONLY_MODE] ?: true
        )
    }

    val notifications: Flow<List<DocumentNotification>> = context.folioSettingsStore.data.map { prefs ->
        decodeNotifications(prefs[Keys.NOTIFICATIONS_JSON])
            .sortedByDescending { it.timestampMillis }
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

    suspend fun setPageSoundEnabled(enabled: Boolean) {
        context.folioSettingsStore.edit { it[Keys.PAGE_SOUND_ENABLED] = enabled }
    }

    suspend fun setBanglaOnlyMode(enabled: Boolean) {
        context.folioSettingsStore.edit { it[Keys.BANGLA_ONLY_MODE] = enabled }
    }

    /**
     * Checks incoming catalog documents against previously seen IDs and emits
     * persistent notifications for newly added PDFs.
     */
    suspend fun recordNewlyAddedDocuments(
        incoming: List<DocumentNotification>,
        seedKnownIds: Collection<String> = emptyList()
    ) {
        if (incoming.isEmpty() && seedKnownIds.isEmpty()) return
        context.folioSettingsStore.edit { prefs ->
            val knownIds = decodeStringSet(prefs[Keys.KNOWN_CATALOG_IDS_JSON]).toMutableSet()
            knownIds.addAll(seedKnownIds)

            val existingNotifications = decodeNotifications(prefs[Keys.NOTIFICATIONS_JSON]).toMutableList()
            val added = incoming.filter { it.bookId !in knownIds }
            if (added.isNotEmpty()) {
                existingNotifications.addAll(0, added)
                knownIds.addAll(added.map { it.bookId })
            } else {
                knownIds.addAll(incoming.map { it.bookId })
            }

            prefs[Keys.KNOWN_CATALOG_IDS_JSON] = json.encodeToString(knownIds.toList())
            prefs[Keys.NOTIFICATIONS_JSON] = json.encodeToString(existingNotifications.take(50))
        }
    }

    suspend fun addNotification(notification: DocumentNotification) {
        context.folioSettingsStore.edit { prefs ->
            val knownIds = decodeStringSet(prefs[Keys.KNOWN_CATALOG_IDS_JSON]).toMutableSet()
            knownIds.add(notification.bookId)
            val list = decodeNotifications(prefs[Keys.NOTIFICATIONS_JSON]).toMutableList()
            list.add(0, notification)
            prefs[Keys.KNOWN_CATALOG_IDS_JSON] = json.encodeToString(knownIds.toList())
            prefs[Keys.NOTIFICATIONS_JSON] = json.encodeToString(list.take(50))
        }
    }

    suspend fun markAllNotificationsRead() {
        context.folioSettingsStore.edit { prefs ->
            val list = decodeNotifications(prefs[Keys.NOTIFICATIONS_JSON])
            if (list.any { it.isUnread }) {
                val updated = list.map { it.copy(isUnread = false) }
                prefs[Keys.NOTIFICATIONS_JSON] = json.encodeToString(updated)
            }
        }
    }

    suspend fun deleteNotification(notificationId: String) {
        context.folioSettingsStore.edit { prefs ->
            val updated = decodeNotifications(prefs[Keys.NOTIFICATIONS_JSON])
                .filterNot { it.id == notificationId }
            prefs[Keys.NOTIFICATIONS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearAllNotifications() {
        context.folioSettingsStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_JSON] = "[]"
        }
    }

    private fun decodeNotifications(raw: String?): List<DocumentNotification> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<DocumentNotification>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun decodeStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }
            .getOrDefault(emptySet())
    }
}
