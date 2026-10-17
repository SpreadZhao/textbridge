package io.github.textbridge.android

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

data class SendOptions(
    val mode: SendMode = SendMode.SEND_ONLY,
    val combination: KeyCombination = KeyCombination(),
)

/** Read legacy Enter settings and save the complete action in one DataStore edit. */
object SendOptionsPreferences {
    private val modeKey = stringPreferencesKey("send_mode")
    private val keyKey = stringPreferencesKey("after_send_key")
    private val modifiersKey = stringSetPreferencesKey("after_send_modifiers")

    fun read(preferences: Preferences): SendOptions {
        val storedMode = preferences[modeKey]
        if (storedMode == "send_then_enter") {
            return SendOptions(SendMode.SEND_THEN_KEY)
        }
        val storedKey = preferences[keyKey]
        val key = RemoteKey.fromWireValue(storedKey)
        val modifiers = preferences[modifiersKey].orEmpty()
        if ((storedKey != null && key == null) ||
            modifiers.any { value -> KeyModifier.entries.none { it.wireValue == value } }
        ) {
            return SendOptions()
        }
        return SendOptions(
            mode = SendMode.fromStorage(storedMode),
            combination = KeyCombination(
                key = key ?: RemoteKey.RETURN,
                modifiers = KeyModifier.entries.filter { it.wireValue in modifiers }.toSet(),
            ),
        )
    }

    fun write(preferences: MutablePreferences, options: SendOptions) {
        preferences[modeKey] = options.mode.storageValue
        preferences[keyKey] = options.combination.key.wireValue
        preferences[modifiersKey] = options.combination.orderedModifiers.map { it.wireValue }.toSet()
    }
}
