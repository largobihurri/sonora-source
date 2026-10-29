package io.sonora.core.playback.output

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * This stored setting as the player should follow it: [neutral] (off, no speed change) while [armed] holds, the stored
 * value otherwise. The stored value itself is never touched, so it is back as soon as bit-perfect output is not armed.
 */
internal fun <T> Flow<T>.lockedTo(neutral: T, armed: Flow<Boolean>): Flow<T> =
    combine(this, armed) { stored, locked -> if (locked) neutral else stored }
