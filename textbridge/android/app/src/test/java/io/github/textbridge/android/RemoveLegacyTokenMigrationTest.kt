package io.github.textbridge.android

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveLegacyTokenMigrationTest {
    @Test
    fun removesLegacyTokenAndPreservesSettingsAndHistory() = runTest {
        val token = stringPreferencesKey("token")
        val address = stringPreferencesKey("address")
        val port = intPreferencesKey("adb_port")
        val history = stringPreferencesKey("send_history_json")
        val savedHistory = "[{\"id\":\"saved\",\"text\":\"保留的历史\"}]"
        val existing = preferencesOf(token to "old-token", address to "desktop:17321", port to 18000, history to savedHistory)

        assertTrue(RemoveLegacyTokenMigration.shouldMigrate(existing))
        val migrated = RemoveLegacyTokenMigration.migrate(existing)

        assertFalse(migrated.contains(token))
        assertEquals("desktop:17321", migrated[address])
        assertEquals(18000, migrated[port])
        assertEquals(savedHistory, migrated[history])
        assertEquals(existing.asMap() - token, migrated.asMap())
        assertFalse(RemoveLegacyTokenMigration.shouldMigrate(migrated))
        assertEquals("old-token", existing[token])
    }

    @Test
    fun settingsWithoutTokenDoNotNeedMigration() = runTest {
        val existing = preferencesOf(stringPreferencesKey("transport_mode") to "bluetooth")

        assertFalse(RemoveLegacyTokenMigration.shouldMigrate(existing))
        assertEquals(existing, RemoveLegacyTokenMigration.migrate(existing))
    }
}
