package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import io.sonora.core.domain.port.VisualCollectionPreferencesRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [VisualCollectionPreferencesRepository] in a preferences DataStore file of its own (not the app settings file). A missing
 * or unreadable value reads as the default.
 */
class DataStoreVisualCollectionPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : VisualCollectionPreferencesRepository {

    override val hideAlbumsWithoutArtwork: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Visual collection preferences unreadable; using defaults", e)
            emit(emptyPreferences())
        }
        .map { it[HIDE_ALBUMS_WITHOUT_ARTWORK] ?: false }
        .distinctUntilChanged()

    override suspend fun setHideAlbumsWithoutArtwork(hide: Boolean) {
        dataStore.edit { it[HIDE_ALBUMS_WITHOUT_ARTWORK] = hide }
    }

    private companion object {
        const val TAG = "SonoraVisualCollection"

        /** Persisted: never rename it. */
        val HIDE_ALBUMS_WITHOUT_ARTWORK = booleanPreferencesKey("cover_flow.hide_albums_without_artwork")
    }
}
