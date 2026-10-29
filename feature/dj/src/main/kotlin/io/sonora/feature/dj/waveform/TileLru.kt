package io.sonora.feature.dj.waveform

/**
 * A small least-recently-used cache of tiles keyed by tile index, read on every drawn frame, so it never allocates on
 * a read: keys are primitive ints in fixed arrays (a `LinkedHashMap<Int, …>` would box every index above 127) and a
 * lookup is a scan of at most [capacity] slots (about a dozen).
 *
 * A key can be present with a null value: a tile whose rendering is under way ("pending"). [get] reads it as null,
 * [contains] as present, so it is not requested twice.
 *
 * When full, [put] evicts the least recently used tile outside the protected range (the tiles the view wants now), or
 * the least recently used one of all when every slot is protected. Not thread-safe: the main thread owns it.
 */
class TileLru<V : Any>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    @PublishedApi
    internal val keys: IntArray = IntArray(capacity)

    @PublishedApi
    internal val values: Array<Any?> = arrayOfNulls(capacity)

    @PublishedApi
    internal val used: BooleanArray = BooleanArray(capacity)

    private val lastUse = LongArray(capacity)
    private var tick = 0L

    /** Number of tiles held, pending ones included. */
    var size: Int = 0
        private set

    /** The tile [key] (and marks it used), or null when absent or pending. */
    fun get(key: Int): V? {
        val slot = slotOf(key)
        if (slot < 0) return null
        lastUse[slot] = ++tick
        @Suppress("UNCHECKED_CAST")
        return values[slot] as V?
    }

    /** Whether [key] is held, ready or pending. Does not mark it used. */
    fun contains(key: Int): Boolean = slotOf(key) >= 0

    /**
     * Stores [value] (null for pending) under [key] as the most recently used tile and returns the key it evicted to
     * make room, or [NO_KEY]. Keys in `[protectFirst, protectLast]` are evicted only when nothing else can go.
     */
    fun put(key: Int, value: V?, protectFirst: Int, protectLast: Int): Int {
        val existing = slotOf(key)
        if (existing >= 0) {
            values[existing] = value
            lastUse[existing] = ++tick
            return NO_KEY
        }
        var evicted = NO_KEY
        var slot = freeSlot()
        if (slot < 0) {
            slot = victim(protectFirst, protectLast)
            evicted = keys[slot]
            size--
        }
        keys[slot] = key
        values[slot] = value
        used[slot] = true
        lastUse[slot] = ++tick
        size++
        return evicted
    }

    /** Drops [key] if held. */
    fun remove(key: Int) {
        val slot = slotOf(key)
        if (slot >= 0) free(slot)
    }

    /** Drops every tile. */
    fun clear() {
        for (slot in 0 until capacity) if (used[slot]) free(slot)
    }

    /** Calls [action] for every ready (non-pending) tile, in slot order. Does not mark them used. */
    inline fun forEachReady(action: (key: Int, value: V) -> Unit) {
        for (slot in keys.indices) {
            val value = values[slot]
            @Suppress("UNCHECKED_CAST")
            if (used[slot] && value != null) action(keys[slot], value as V)
        }
    }

    private fun slotOf(key: Int): Int {
        for (slot in 0 until capacity) if (used[slot] && keys[slot] == key) return slot
        return -1
    }

    private fun freeSlot(): Int {
        for (slot in 0 until capacity) if (!used[slot]) return slot
        return -1
    }

    private fun victim(protectFirst: Int, protectLast: Int): Int {
        var oldestFree = -1
        var oldestAny = -1
        for (slot in 0 until capacity) {
            if (oldestAny < 0 || lastUse[slot] < lastUse[oldestAny]) oldestAny = slot
            val protected = keys[slot] in protectFirst..protectLast
            if (!protected && (oldestFree < 0 || lastUse[slot] < lastUse[oldestFree])) oldestFree = slot
        }
        return if (oldestFree >= 0) oldestFree else oldestAny
    }

    private fun free(slot: Int) {
        used[slot] = false
        values[slot] = null
        size--
    }

    companion object {
        /** "No key": returned by [put] when nothing was evicted. Never a real tile index. */
        const val NO_KEY: Int = Int.MIN_VALUE
    }
}
