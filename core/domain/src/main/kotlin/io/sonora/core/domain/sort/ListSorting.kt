package io.sonora.core.domain.sort

import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SortPreferencesRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Applies each list's saved sort ([SortPreferencesRepository]) in the app's language ([PreferencesRepository]):
 * text collates the way the language the user reads the app in expects. Sorting runs on [dispatcher], never on the
 * main thread.
 */
class ListSorting(
    private val store: SortPreferencesRepository,
    preferences: PreferencesRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val locale: Flow<Locale> = preferences.preferences
        .map { Locale.forLanguageTag(it.language.tag) }
        .distinctUntilChanged()

    /** [target]'s order: the stored one when its list offers it, else the list's default. */
    fun order(target: SortTarget): Flow<SortOrder> = store.order(target).map(target.list::valid).distinctUntilChanged()

    /** [source] sorted by [sort] with [target]'s order, again whenever the source, the order or the language changes. */
    fun <T, R> sorted(source: Flow<T>, target: SortTarget, sort: (T, SortOrder, Locale) -> R): Flow<Sorted<R>> =
        combine(source, order(target), locale) { items, order, locale -> Sorted(order, sort(items, order, locale)) }
            .flowOn(dispatcher)

    suspend fun select(target: SortTarget, order: SortOrder) = store.setOrder(target, target.list.valid(order))

    suspend fun forget(target: SortTarget) = store.clear(target)
}
