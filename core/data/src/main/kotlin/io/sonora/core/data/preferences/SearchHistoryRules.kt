package io.sonora.core.data.preferences

/** The pure rules of the recent searches list: most recent first, unique ignoring case, at most [LIMIT] entries. */
internal object SearchHistoryRules {
    const val LIMIT = 20

    /** Entries are stored one per line; [normalize] removes every line break from them. */
    private const val SEPARATOR = "\n"
    private val whitespace = Regex("\\s+")

    /** [query] trimmed, with its line breaks and runs of whitespace as one space. */
    fun normalize(query: String): String = query.trim().replace(whitespace, " ")

    /** [history] with [query] first, its earlier entry (ignoring case) dropped and the oldest past [LIMIT] forgotten. */
    fun record(history: List<String>, query: String): List<String> {
        val entry = normalize(query)
        if (entry.isEmpty()) return history
        return cleaned(listOf(entry) + history)
    }

    /** [history] without the entry equal to [query] ignoring case. */
    fun remove(history: List<String>, query: String): List<String> {
        val entry = normalize(query)
        return history.filterNot { it.equals(entry, ignoreCase = true) }
    }

    fun encode(history: List<String>): String = history.joinToString(SEPARATOR)

    /** The stored [stored] value as a list; a missing value, blank lines, duplicates and overflow are dropped. */
    fun decode(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        return cleaned(stored.split(SEPARATOR).map(::normalize).filter { it.isNotEmpty() })
    }

    /** The first of each case-insensitive group, at most [LIMIT] of them. */
    private fun cleaned(entries: List<String>): List<String> =
        entries.distinctBy { it.lowercase() }.take(LIMIT)
}
