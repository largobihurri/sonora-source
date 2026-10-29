package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import io.sonora.core.domain.playback.Crossfade
import io.sonora.core.domain.playback.CrossfadeRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [CrossfadeRepository] in a preferences DataStore file of its own (the playback service reads it): the length in
 * whole seconds under `playback.crossfade.seconds`. A missing or unreadable value reads as off; a value out of range
 * reads at the nearest end. A failed write throws, and readers keep what the file holds.
 */
class DataStoreCrossfadeRepository(private val dataStore: DataStore<Preferences>) : CrossfadeRepository {

    override val seconds: Flow<Int> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Crossfade setting unreadable; using the default", e)
            emit(emptyPreferences())
        }
        .map { Crossfade.clampSeconds(it[SECONDS] ?: Crossfade.DEFAULT_SECONDS) }
        .distinctUntilChanged()

    override suspend fun setSeconds(seconds: Int) {
        dataStore.edit { it[SECONDS] = Crossfade.clampSeconds(seconds) }
    }

    private companion object {
        const val TAG = "SonoraCrossfade"

        // The key name is persisted: never change it.
        val SECONDS = intPreferencesKey("playback.crossfade.seconds")
    }
}
