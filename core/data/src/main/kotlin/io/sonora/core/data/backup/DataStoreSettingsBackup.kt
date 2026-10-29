package io.sonora.core.data.backup

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.BackupSettingsPolicy
import io.sonora.core.domain.backup.SettingValue
import io.sonora.core.domain.port.SettingsBackupStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * [SettingsBackupStore] over the app's preferences DataStore files, each named in a backup by [files]' key. Only the
 * files listed here are ever read or written: a folder grant or a secret kept in another file never enters a backup.
 * Values are carried raw, so a setting added later is backed up without changes here.
 */
class DataStoreSettingsBackup(private val files: Map<String, DataStore<Preferences>>) : SettingsBackupStore {

    override suspend fun read(): List<BackupSettingsFile> = files.map { (name, store) ->
        val stored = try {
            store.data.first().asMap()
        } catch (e: IOException) {
            Log.w(TAG, "Settings file $name unreadable; backing up none of it", e)
            emptyMap()
        }
        BackupSettingsFile(name, stored.entries.mapNotNull { (key, value) -> value.toSetting()?.let { key.name to it } }.toMap())
    }

    override suspend fun merge(files: List<BackupSettingsFile>) {
        for (file in files) {
            val store = this.files[file.name] ?: continue
            store.edit { stored ->
                val current = stored.asMap().mapKeys { it.key.name }
                for ((key, value) in file.values) {
                    if (!BackupSettingsPolicy.includes(key)) continue
                    // A value whose type differs from the stored one would fail every later read of that setting.
                    val existing = current[key]
                    if (existing != null && existing.toSetting()?.javaClass != value.javaClass) continue
                    stored.put(key, value)
                }
            }
        }
    }

    private fun MutablePreferences.put(key: String, value: SettingValue) {
        when (value) {
            is SettingValue.BooleanValue -> this[booleanPreferencesKey(key)] = value.value
            is SettingValue.IntValue -> this[intPreferencesKey(key)] = value.value
            is SettingValue.LongValue -> this[longPreferencesKey(key)] = value.value
            is SettingValue.FloatValue -> this[floatPreferencesKey(key)] = value.value
            is SettingValue.DoubleValue -> this[doublePreferencesKey(key)] = value.value
            is SettingValue.StringValue -> this[stringPreferencesKey(key)] = value.value
            is SettingValue.StringSetValue -> this[stringSetPreferencesKey(key)] = value.value
        }
    }

    /** The stored [Any] as a typed value; `null` for a type a backup does not carry. */
    private fun Any.toSetting(): SettingValue? = when (this) {
        is Boolean -> SettingValue.BooleanValue(this)
        is Int -> SettingValue.IntValue(this)
        is Long -> SettingValue.LongValue(this)
        is Float -> SettingValue.FloatValue(this)
        is Double -> SettingValue.DoubleValue(this)
        is String -> SettingValue.StringValue(this)
        is Set<*> -> if (all { it is String }) SettingValue.StringSetValue(filterIsInstance<String>().toSet()) else null
        else -> null
    }

    private companion object {
        const val TAG = "SonoraBackup"
    }
}
