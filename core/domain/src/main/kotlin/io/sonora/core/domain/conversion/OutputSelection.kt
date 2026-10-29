package io.sonora.core.domain.conversion

/**
 * The converter's output choice for a batch being set up: one [batchWide] value, values set by hand for some picked
 * sources (a song or a folder, by its uri) and, inside a folder, for some of its files. An entry follows the value
 * above it (a file its folder's, a source the batch-wide one) until it is set by hand; from then on it keeps its own
 * value, whatever the value above it becomes, until it is cleared. Immutable: every change returns a new selection.
 */
data class OutputSelection<T>(
    val batchWide: T,
    private val sources: Map<String, T> = emptyMap(),
    private val children: Map<String, Map<String, T>> = emptyMap(),
) {

    /** The value [child] of [source] (or [source] itself, when [child] is `null`) converts to. */
    fun resolve(source: String, child: String? = null): T {
        if (child != null && child != source) children[source]?.get(child)?.let { return it }
        return sources[source] ?: batchWide
    }

    /** Whether the entry was set by hand (and shows a mark): it no longer follows the value above it. */
    fun isManual(source: String, child: String? = null): Boolean =
        if (child != null && child != source) children[source]?.containsKey(child) == true else source in sources

    /** How many files of folder [source] were set by hand. */
    fun manualChildCount(source: String): Int = children[source]?.size ?: 0

    /** A new batch-wide value; entries set by hand keep theirs. */
    fun withBatchWide(value: T): OutputSelection<T> = copy(batchWide = value)

    /** Sets [source] by hand; its files set by hand keep their own values. */
    fun withSource(source: String, value: T): OutputSelection<T> = copy(sources = sources + (source to value))

    /** Sets [child] of folder [source] by hand. */
    fun withChild(source: String, child: String, value: T): OutputSelection<T> {
        if (child == source) return withSource(source, value)
        val inside = children[source].orEmpty() + (child to value)
        return copy(children = children + (source to inside))
    }

    /** Puts [source] back on the batch-wide value; its files set by hand keep theirs. */
    fun clearSource(source: String): OutputSelection<T> = copy(sources = sources - source)

    /** Puts [child] of [source] back on its folder's value. */
    fun clearChild(source: String, child: String): OutputSelection<T> {
        if (child == source) return clearSource(source)
        val inside = children[source]?.minus(child) ?: return this
        return copy(children = if (inside.isEmpty()) children - source else children + (source to inside))
    }

    /** Forgets [source] and its files, as when it is removed from the batch. */
    fun removeSource(source: String): OutputSelection<T> = copy(sources = sources - source, children = children - source)
}
