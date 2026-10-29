package io.sonora.core.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * The searches the user submitted or opened a result from, most recent first, kept across restarts. Entries are
 * unique ignoring case, and only the most recent ones are kept.
 */
interface SearchHistoryRepository {
    /** The recent searches, most recent first; re-emits on every change. */
    val recent: Flow<List<String>>

    /** Puts [query] first (replacing an entry equal to it ignoring case); a blank [query] is ignored. */
    suspend fun record(query: String)

    /** Forgets the entry equal to [query] ignoring case. */
    suspend fun remove(query: String)

    /** Forgets every recent search. */
    suspend fun clear()
}
