package io.sonora.core.domain.library.marks

/** A list with its pinned items moved to the top ([items]) and which of them are pinned ([pinnedKeys]). */
data class Pinned<T>(val items: List<T>, val pinnedKeys: Set<String>) {
    fun isPinned(key: String): Boolean = key in pinnedKeys
}

/** Pinned items sit above the list's chosen sort. */
object PinOrdering {
    /**
     * [items] (already in their sort) with the ones whose [key] is in [pins] first, in pin order; the rest keep their
     * order. Items sharing a key (a song listed twice in a playlist) move up together. Pins of items not in the list
     * are ignored.
     */
    fun <T> apply(items: List<T>, pins: List<String>, key: (T) -> String): Pinned<T> {
        if (pins.isEmpty() || items.isEmpty()) return Pinned(items, emptySet())
        val rank = HashMap<String, Int>(pins.size)
        pins.forEachIndexed { index, pin -> rank.putIfAbsent(pin, index) }
        val (pinned, rest) = items.partition { key(it) in rank }
        // sortedBy is stable: items sharing a pin keep their own order.
        val top = pinned.sortedBy { rank.getValue(key(it)) }
        return Pinned(top + rest, top.mapTo(LinkedHashSet()) { key(it) })
    }
}
