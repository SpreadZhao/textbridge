package io.github.textbridge.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val LEGACY_PREFS_NAME = "textbridge"
private const val SETTINGS_NAME = "textbridge_settings"

private val Context.textBridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, LEGACY_PREFS_NAME))
    },
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val settings: Flow<TextBridgeSettings> = appContext.textBridgeDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            TextBridgeSettings(
                address = preferences[Keys.address].orEmpty(),
                discoveryPort = preferences[Keys.discoveryPort] ?: DEFAULT_DISCOVERY_PORT,
                token = preferences[Keys.token].orEmpty(),
                sendHistory = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson]),
            )
        }

    suspend fun saveAddress(address: String) {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.address] = address
        }
    }

    suspend fun saveDiscoveryPort(port: Int) {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.discoveryPort] = port
        }
    }

    suspend fun saveAddressAndToken(address: String, token: String) {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.address] = address
            preferences[Keys.token] = token
        }
    }

    suspend fun addSendHistoryItem(item: SendHistoryItem): List<SendHistoryItem> {
        var updated = emptyList<SendHistoryItem>()
        appContext.textBridgeDataStore.edit { preferences ->
            val current = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson])
            updated = SendHistoryCodec.prepend(current, item)
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(updated)
        }
        return updated
    }

    suspend fun removeSendHistoryItem(itemId: String): List<SendHistoryItem> {
        var updated = emptyList<SendHistoryItem>()
        appContext.textBridgeDataStore.edit { preferences ->
            val current = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson])
            updated = SendHistoryCodec.remove(current, itemId)
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(updated)
        }
        return updated
    }

    suspend fun clearSendHistory() {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(emptyList())
        }
    }

    private object Keys {
        val address = stringPreferencesKey("address")
        val discoveryPort = intPreferencesKey("discovery_port")
        val token = stringPreferencesKey("token")
        val sendHistoryJson = stringPreferencesKey("send_history_json")
    }
}
