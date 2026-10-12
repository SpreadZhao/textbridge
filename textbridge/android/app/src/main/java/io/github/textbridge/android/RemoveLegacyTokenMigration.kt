package io.github.textbridge.android

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** Runs after SharedPreferences migration so neither storage format retains the old token. */
internal object RemoveLegacyTokenMigration : DataMigration<Preferences> {
    private val legacyToken = stringPreferencesKey("token")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.contains(legacyToken)

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        remove(legacyToken)
    }.toPreferences()

    override suspend fun cleanUp() = Unit
}
