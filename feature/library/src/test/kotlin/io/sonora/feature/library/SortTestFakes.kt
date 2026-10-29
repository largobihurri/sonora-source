package io.sonora.feature.library

import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SortPreferencesRepository
import io.sonora.core.domain.sort.ListSorting
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Sorts kept in memory; while [failure] is set, saving a sort throws it. */
internal class FakeSortStore : SortPreferencesRepository {
    val stored = MutableStateFlow<Map<SortTarget, SortOrder>>(emptyMap())
    var failure: Exception? = null

    override fun order(target: SortTarget): Flow<SortOrder?> = stored.map { it[target] }

    override suspend fun setOrder(target: SortTarget, order: SortOrder) {
        failure?.let { throw it }
        stored.value += target to order
    }

    override suspend fun clear(target: SortTarget) {
        stored.value -= target
    }
}

internal class FakeAppPreferences(language: AppLanguage = AppLanguage.ENGLISH) : PreferencesRepository {
    private val state = MutableStateFlow(AppPreferences(language = language))
    override val preferences: Flow<AppPreferences> = state

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        state.value = transform(state.value)
    }
}

/**
 * [ListSorting] over [store] that sorts on the calling thread, so tests see results at once; pass an unconfined test
 * [dispatcher] when the sorted source waits (a delay there then runs on the test's virtual time).
 */
internal fun testSorting(store: FakeSortStore = FakeSortStore(), dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
    ListSorting(store, FakeAppPreferences(), dispatcher)
