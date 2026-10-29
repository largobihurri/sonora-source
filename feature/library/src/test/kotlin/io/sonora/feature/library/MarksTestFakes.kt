package io.sonora.feature.library

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [LibraryMarksRepository]; [forgotten] records the songs whose marks were dropped. */
internal class FakeLibraryMarks : LibraryMarksRepository {
    val favourites = MutableStateFlow<List<ItemRef>>(emptyList())
    val pins = MutableStateFlow<List<Pair<PinContext, ItemRef>>>(emptyList())
    val hidden = MutableStateFlow<List<HiddenItem>>(emptyList())
    val forgotten = mutableListOf<String>()
    override fun favourites(kind: MarkKind): Flow<List<String>> = favourites.map { all -> all.filter { it.kind == kind }.map { it.key } }
    override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) {
        favourites.value = favourites.value - ref + listOfNotNull(ref.takeIf { favourite })
    }
    override fun pins(context: PinContext): Flow<List<String>> = pins.map { all -> all.filter { it.first == context }.map { it.second.key } }
    override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) {
        pins.value = pins.value - (context to ref) + listOfNotNull((context to ref).takeIf { pinned })
    }
    override fun hidden(): Flow<List<HiddenItem>> = hidden
    override suspend fun hide(item: HiddenItem) {
        hidden.value += item
    }
    override suspend fun restore(ref: ItemRef) {
        hidden.value = hidden.value.filterNot { it.ref == ref }
    }
    override suspend fun forgetSongs(uris: Collection<String>) {
        forgotten += uris
    }
}
