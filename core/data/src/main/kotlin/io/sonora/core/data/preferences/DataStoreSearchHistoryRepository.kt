package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.port.SearchHistoryRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [SearchHistoryRepository] in a preferences DataStore file of its own: one key holding the entries one per line,
 * shaped by [SearchHistoryRules]. An unreadable file reads as no history.
 */
class DataStoreSearchHistoryRepository(
    private val dataStore: DataStore<Preferences>,
) : SearchHistoryRepository {

    override val recent: Flow<List<String>> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Search history unreadable; starting empty", e)
            emit(emptyPreferences())
        }
        .map { SearchHistoryRules.decode(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun record(query: String) = change { SearchHistoryRules.record(it, query) }

    override suspend fun remove(query: String) = change { SearchHistoryRules.remove(it, query) }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private suspend fun change(block: (List<String>) -> List<String>) {
        dataStore.edit { it[KEY] = SearchHistoryRules.encode(block(SearchHistoryRules.decode(it[KEY]))) }
    }

    private companion object {
        const val TAG = "SonoraSearchHistory"

        /** Persisted: never change it. */
        val KEY = stringPreferencesKey("search.recent")
    }
}
