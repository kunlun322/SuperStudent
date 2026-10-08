package com.superstudent.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "superstudent_prefs")

/** Non-sensitive UI/session prefs only. PAT lives in CredentialStore; identity in Room. */
class Prefs(private val context: Context) {

    private object Keys {
        val CURRENT_IDENTITY = stringPreferencesKey("current_identity_id")
        val CURRENT_USERNAME = stringPreferencesKey("current_username")
        val LAST_TAB = stringPreferencesKey("last_tab")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val CARD_IMAGES_ENABLED = booleanPreferencesKey("card_images_enabled")
        val PREFETCH_WIFI_ONLY = booleanPreferencesKey("prefetch_wifi_only")
    }

    val currentIdentityId: Flow<String?> =
        context.prefsDataStore.data.map { it[Keys.CURRENT_IDENTITY] }

    val currentUsername: Flow<String?> =
        context.prefsDataStore.data.map { it[Keys.CURRENT_USERNAME] }

    val lastTab: Flow<String> =
        context.prefsDataStore.data.map { it[Keys.LAST_TAB] ?: "study" }

    val cardImagesEnabled: Flow<Boolean> =
        context.prefsDataStore.data.map { it[Keys.CARD_IMAGES_ENABLED] ?: true }

    val prefetchWifiOnly: Flow<Boolean> =
        context.prefsDataStore.data.map { it[Keys.PREFETCH_WIFI_ONLY] ?: true }

    suspend fun setCardImagesEnabled(enabled: Boolean) {
        context.prefsDataStore.edit { it[Keys.CARD_IMAGES_ENABLED] = enabled }
    }

    suspend fun setPrefetchWifiOnly(wifiOnly: Boolean) {
        context.prefsDataStore.edit { it[Keys.PREFETCH_WIFI_ONLY] = wifiOnly }
    }

    /** One-shot read used when building a task payload, so generation matches the current toggle. */
    suspend fun cardImagesEnabledNow(): Boolean =
        context.prefsDataStore.data.first()[Keys.CARD_IMAGES_ENABLED] ?: true

    suspend fun setCurrentAccount(identityId: String, username: String) {
        context.prefsDataStore.edit {
            it[Keys.CURRENT_IDENTITY] = identityId
            it[Keys.CURRENT_USERNAME] = username
        }
    }

    suspend fun clearCurrentAccount() {
        context.prefsDataStore.edit {
            it.remove(Keys.CURRENT_IDENTITY)
            it.remove(Keys.CURRENT_USERNAME)
        }
    }

    suspend fun snapshotAccount(): Pair<String, String?> = run {
        val p = context.prefsDataStore.data.first()
        (p[Keys.CURRENT_IDENTITY] ?: "") to p[Keys.CURRENT_USERNAME]
    }

    suspend fun setLastTab(tab: String) {
        context.prefsDataStore.edit { it[Keys.LAST_TAB] = tab }
    }

    suspend fun markSynced(epochMillis: Long) {
        context.prefsDataStore.edit { it[Keys.LAST_SYNC_AT] = epochMillis }
    }
}
