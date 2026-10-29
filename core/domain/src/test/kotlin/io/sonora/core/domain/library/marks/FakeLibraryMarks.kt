package io.sonora.core.domain.library.marks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [LibraryMarksRepository]. */
internal class FakeLibraryMarks : LibraryMarksRepository {
    val favourites = MutableStateFlow<List<ItemRef>>(emptyList())
    val pins = MutableStateFlow<List<Pair<PinContext, ItemRef>>>(emptyList())
    val hidden = MutableStateFlow<List<HiddenItem>>(emptyList())

    override fun favourites(kind: MarkKind): Flow<List<String>> =
        favourites.map { all -> all.filter { it.kind == kind }.map { it.key }.reversed() }

    override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) {
        favourites.value = favourites.value - ref + listOfNotNull(ref.takeIf { favourite })
    }

    override fun pins(context: PinContext): Flow<List<String>> =
        pins.map { all -> all.filter { it.first == context }.map { it.second.key } }

    override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) {
        val pin = context to ref
        pins.value = pins.value - pin + listOfNotNull(pin.takeIf { pinned })
    }

    override fun hidden(): Flow<List<HiddenItem>> = hidden

    override suspend fun hide(item: HiddenItem) {
        hidden.value = listOf(item) + hidden.value.filterNot { it.ref == item.ref }
    }

    override suspend fun restore(ref: ItemRef) {
        hidden.value = hidden.value.filterNot { it.ref == ref }
    }

    override suspend fun forgetSongs(uris: Collection<String>) {
        val songs = uris.map(ItemRef::song).toSet()
        favourites.value = favourites.value.filterNot { it in songs }
        pins.value = pins.value.filterNot { it.second in songs }
        hidden.value = hidden.value.filterNot { it.ref in songs }
    }

    /** Every [moveSongs] call, in order. */
    val moves = mutableListOf<Map<String, String>>()

    override suspend fun moveSongs(moves: Map<String, String>) {
        this.moves += moves
        for ((from, to) in moves) {
            val kept = ItemRef.song(to)
            if (ItemRef.song(from) in favourites.value && kept !in favourites.value) favourites.value += kept
            for ((context, ref) in pins.value) {
                if (ref == ItemRef.song(from) && context to kept !in pins.value) pins.value += context to kept
            }
        }
        forgetSongs(moves.keys)
    }
}
