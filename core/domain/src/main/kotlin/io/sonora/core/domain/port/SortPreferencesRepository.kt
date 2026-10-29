package io.sonora.core.domain.port

import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import kotlinx.coroutines.flow.Flow

/** The sort the user chose for each list, kept apart from the app settings and surviving restarts. */
interface SortPreferencesRepository {
    /** [target]'s stored order, `null` while the user never chose one; re-emits on every change. */
    fun order(target: SortTarget): Flow<SortOrder?>

    suspend fun setOrder(target: SortTarget, order: SortOrder)

    /** Forgets [target]'s order (a deleted playlist), so it reads as never chosen. */
    suspend fun clear(target: SortTarget)
}
