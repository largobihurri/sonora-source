package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.port.SortPreferencesRepository
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [SortPreferencesRepository] in a preferences DataStore file of its own (not the app settings file), two keys per
 * list: `sort.<list>[.<playlist id>].key` and `.direction`, holding the enum names. A missing, unknown or unreadable
 * value reads as never chosen.
 */
class DataStoreSortPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : SortPreferencesRepository {

    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(TAG, "Sort preferences unreadable; using defaults", e)
        emit(emptyPreferences())
    }

    override fun order(target: SortTarget): Flow<SortOrder?> {
        val (key, direction) = keys(target)
        return data.map { stored ->
            val sortKey = stored[key]?.let { name -> SortKey.entries.firstOrNull { it.name == name } }
            val sortDirection = stored[direction]?.let { name -> SortDirection.entries.firstOrNull { it.name == name } }
            if (sortKey == null || sortDirection == null) null else SortOrder(sortKey, sortDirection)
        }.distinctUntilChanged()
    }

    override suspend fun setOrder(target: SortTarget, order: SortOrder) {
        val (key, direction) = keys(target)
        dataStore.edit {
            it[key] = order.key.name
            it[direction] = order.direction.name
        }
    }

    override suspend fun clear(target: SortTarget) {
        val (key, direction) = keys(target)
        dataStore.edit {
            it.remove(key)
            it.remove(direction)
        }
    }

    /** Key names are persisted: never change them. */
    private fun keys(target: SortTarget): Pair<Preferences.Key<String>, Preferences.Key<String>> {
        val list = target.list.name.lowercase(Locale.ROOT)
        val prefix = if (target.playlistId == null) "sort.$list" else "sort.$list.${target.playlistId}"
        return stringPreferencesKey("$prefix.key") to stringPreferencesKey("$prefix.direction")
    }

    private companion object {
        const val TAG = "SonoraSort"
    }
}
