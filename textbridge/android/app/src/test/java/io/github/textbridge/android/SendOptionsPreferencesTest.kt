package io.github.textbridge.android

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SendOptionsPreferencesTest {
    @Test
    fun freshInstallDefaultsToSendOnlyWithEnterAvailable() {
        assertEquals(SendOptions(), SendOptionsPreferences.read(mutablePreferencesOf()))
    }

    @Test
    fun legacyEnterPreferencePreservesItsBehavior() {
        val preferences = mutablePreferencesOf(stringPreferencesKey("send_mode") to "send_then_enter")
        assertEquals(SendOptions(SendMode.SEND_THEN_KEY), SendOptionsPreferences.read(preferences))
    }

    @Test
    fun completeCombinationRoundTripsAndRemainsAvailableWhenDisabled() {
        val combination = KeyCombination(RemoteKey.F12, setOf(KeyModifier.ALT, KeyModifier.SHIFT, KeyModifier.CONTROL))
        val preferences = mutablePreferencesOf()
        SendOptionsPreferences.write(preferences, SendOptions(SendMode.SEND_THEN_KEY, combination))
        assertEquals(SendOptions(SendMode.SEND_THEN_KEY, combination), SendOptionsPreferences.read(preferences))
        SendOptionsPreferences.write(preferences, SendOptions(SendMode.SEND_ONLY, combination))
        assertEquals(SendOptions(SendMode.SEND_ONLY, combination), SendOptionsPreferences.read(preferences))
    }

    @Test
    fun invalidStoredKeyOrModifierDisablesAutomaticAction() {
        for (options in listOf("F13" to emptySet(), "Return" to setOf("Super"))) {
            val preferences = mutablePreferencesOf(
                stringPreferencesKey("send_mode") to "send_then_key",
                stringPreferencesKey("after_send_key") to options.first,
                stringSetPreferencesKey("after_send_modifiers") to options.second,
            )
            assertEquals(SendOptions(), SendOptionsPreferences.read(preferences))
        }
    }

    @Test
    fun modifierLabelAndWireOrderAreStable() {
        val combination = KeyCombination(RemoteKey.RETURN, setOf(KeyModifier.ALT, KeyModifier.SHIFT, KeyModifier.CONTROL))
        assertEquals("Ctrl+Shift+Alt+Enter", combination.label)
        assertEquals(listOf(KeyModifier.CONTROL, KeyModifier.SHIFT, KeyModifier.ALT), combination.orderedModifiers)
    }
}
