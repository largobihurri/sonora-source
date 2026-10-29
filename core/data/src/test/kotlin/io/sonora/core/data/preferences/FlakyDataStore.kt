package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * A [real] store that can fail its next [failReads] reads with an [IOException], as a briefly unreadable file does,
 * and can hold every write until the writer is cancelled ([stallWrites]).
 */
class FlakyDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {

    @Volatile var failReads = 0

    @Volatile var stallWrites = false

    /** How many writes are being held by [stallWrites]. */
    val stalledWrites = MutableStateFlow(0)

    override val data: Flow<Preferences> = flow {
        if (failReads > 0) {
            failReads--
            throw IOException("transiently unreadable")
        }
        emitAll(real.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (stallWrites) {
            stalledWrites.update { it + 1 }
            awaitCancellation()
        }
        return real.updateData(transform)
    }
}
