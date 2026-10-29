package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainRepository
import io.sonora.core.domain.playback.ReplayGainSettings
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * [ReplayGainRepository] in a preferences DataStore file of its own: `replay_gain.mode` (the enum name) and
 * `replay_gain.preamp` (dB). A missing, unknown or unreadable value reads as its default; a pre-amp out of range
 * reads as its nearest limit.
 *
 * The settings follow the file: a change stored here or by anyone else writing this store (a backup restore) reaches
 * readers as soon as it is stored, and readers never see a value the file does not hold. A file that cannot be read
 * plays the defaults while it is read again every [readRetryMs]; the defaults are never kept or written back. An update
 * builds on what the file holds, and one that cannot be written throws and leaves the file, and so the settings, as
 * they were.
 */
class DataStoreReplayGainRepository(
    private val dataStore: DataStore<Preferences>,
    private val readRetryMs: Long = READ_RETRY_MS,
) : ReplayGainRepository {

    override val settings: Flow<ReplayGainSettings> = dataStore.data
        .map(::settingsOf)
        .retryWhen { e, _ ->
            if (e !is IOException) return@retryWhen false
            Log.w(TAG, "Volume normalisation settings unreadable; using defaults for now", e)
            emit(ReplayGainSettings())
            delay(readRetryMs)
            true
        }
        .distinctUntilChanged()

    override suspend fun update(transform: (ReplayGainSettings) -> ReplayGainSettings) {
        dataStore.edit {
            val next = transform(settingsOf(it))
            it[MODE] = next.mode.name
            it[PREAMP] = next.preampDb
        }
    }

    private fun settingsOf(stored: Preferences) = ReplayGainSettings(
        mode = stored[MODE]?.let { name -> ReplayGainMode.entries.firstOrNull { it.name == name } } ?: ReplayGainMode.OFF,
        preampDb = stored[PREAMP] ?: 0f,
    )

    private companion object {
        const val TAG = "SonoraReplayGain"

        /** How long an unreadable file plays the defaults before it is read again. */
        const val READ_RETRY_MS = 5_000L

        // Key names are persisted: never change them.
        val MODE = stringPreferencesKey("replay_gain.mode")
        val PREAMP = floatPreferencesKey("replay_gain.preamp")
    }
}
