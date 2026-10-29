package io.sonora.core.data.backup

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import io.sonora.core.domain.port.BackupHistory
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [BackupHistory] in a preferences DataStore file of its own, which is not one of the backed-up settings files: a
 * restore never sets the last backup to the backup's own time.
 */
class DataStoreBackupHistory(private val dataStore: DataStore<Preferences>) : BackupHistory {

    override val lastBackupMs: Flow<Long?> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Backup history unreadable", e)
            emit(emptyPreferences())
        }
        .map { it[LAST_BACKUP_MS] }
        .distinctUntilChanged()

    override suspend fun recordBackup(atMs: Long) {
        dataStore.edit { it[LAST_BACKUP_MS] = atMs }
    }

    private companion object {
        const val TAG = "SonoraBackup"
        val LAST_BACKUP_MS = longPreferencesKey("backup.last_ms")
    }
}
