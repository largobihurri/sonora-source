package io.sonora.feature.library

import io.sonora.core.domain.library.marks.PinOrdering
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.sort.Sorted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A sorted list with the items pinned in its list ([pins]) moved to the top, in pin order. */
internal fun <T> Flow<Sorted<List<T>>>.pinnedBy(pins: Flow<List<String>>, key: (T) -> String): Flow<Sorted<Pinned<T>>> =
    combine(this, pins) { sorted, pinned -> Sorted(sorted.order, PinOrdering.apply(sorted.items, pinned, key)) }

/** [this] list with the items pinned in its list ([pins]) first. */
internal fun <T> Flow<List<T>>.pinned(pins: Flow<List<String>>, key: (T) -> String): Flow<Pinned<T>> =
    combine(this, pins) { items, pinned -> PinOrdering.apply(items, pinned, key) }
