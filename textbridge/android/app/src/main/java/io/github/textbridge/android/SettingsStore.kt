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

interface TextBridgeSettingsRepository {
    val settings: Flow<TextBridgeSettings>

    suspend fun saveConnectionSettings(
        transportMode: TransportMode,
        lanAddress: String,
        discoveryPort: Int,
        adbPort: Int,
        token: String,
    )

    suspend fun addSendHistoryItem(item: SendHistoryItem): List<SendHistoryItem>
    suspend fun removeSendHistoryItem(itemId: String): List<SendHistoryItem>
    suspend fun clearSendHistory()
}

class SettingsStore(context: Context) : TextBridgeSettingsRepository {
    private val appContext = context.applicationContext

    override val settings: Flow<TextBridgeSettings> = appContext.textBridgeDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            TextBridgeSettings(
                transportMode = TransportMode.fromStorage(preferences[Keys.transportMode]),
                lanAddress = preferences[Keys.address].orEmpty(),
                discoveryPort = preferences[Keys.discoveryPort] ?: DEFAULT_DISCOVERY_PORT,
                adbPort = preferences[Keys.adbPort] ?: DEFAULT_COMMIT_PORT,
                token = preferences[Keys.token].orEmpty(),
                sendHistory = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson]),
            )
        }

    override suspend fun saveConnectionSettings(
        transportMode: TransportMode,
        lanAddress: String,
        discoveryPort: Int,
        adbPort: Int,
        token: String,
    ) {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.transportMode] = transportMode.storageValue
            preferences[Keys.address] = lanAddress
            preferences[Keys.discoveryPort] = discoveryPort
            preferences[Keys.adbPort] = adbPort
            preferences[Keys.token] = token
        }
    }

    override suspend fun addSendHistoryItem(item: SendHistoryItem): List<SendHistoryItem> {
        var updated = emptyList<SendHistoryItem>()
        appContext.textBridgeDataStore.edit { preferences ->
            val current = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson])
            updated = SendHistoryCodec.prepend(current, item)
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(updated)
        }
        return updated
    }

    override suspend fun removeSendHistoryItem(itemId: String): List<SendHistoryItem> {
        var updated = emptyList<SendHistoryItem>()
        appContext.textBridgeDataStore.edit { preferences ->
            val current = SendHistoryCodec.decode(preferences[Keys.sendHistoryJson])
            updated = SendHistoryCodec.remove(current, itemId)
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(updated)
        }
        return updated
    }

    override suspend fun clearSendHistory() {
        appContext.textBridgeDataStore.edit { preferences ->
            preferences[Keys.sendHistoryJson] = SendHistoryCodec.encode(emptyList())
        }
    }

    private object Keys {
        val transportMode = stringPreferencesKey("transport_mode")
        val address = stringPreferencesKey("address")
        val discoveryPort = intPreferencesKey("discovery_port")
        val adbPort = intPreferencesKey("adb_port")
        val token = stringPreferencesKey("token")
        val sendHistoryJson = stringPreferencesKey("send_history_json")
    }
}
