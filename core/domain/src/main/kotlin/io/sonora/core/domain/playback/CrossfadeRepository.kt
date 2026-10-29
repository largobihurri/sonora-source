package io.sonora.core.domain.playback

import kotlinx.coroutines.flow.Flow

/** The stored crossfade length (see [Crossfade]); the playback service follows it live. */
interface CrossfadeRepository {

    /** The crossfade length in seconds, within [Crossfade.MIN_SECONDS]..[Crossfade.MAX_SECONDS]; 0 is off. */
    val seconds: Flow<Int>

    /** Stores [seconds], brought within the allowed range first. */
    suspend fun setSeconds(seconds: Int)
}
