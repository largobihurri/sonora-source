package io.sonora.core.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.sonora.core.domain.playback.PlaybackTempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** How often at most a tempo change reaches the player: a 120 Hz fader drag must not flood the session with events. */
internal const val TEMPO_THROTTLE_MS = 33L

/**
 * This tempo as Media3 plays it: the speed factor, and the pitch as a frequency factor (1 keeps the pitch). With
 * Master Tempo off the pitch follows the speed, like a turntable.
 */
internal fun PlaybackTempo.toPlaybackParameters(): PlaybackParameters = PlaybackParameters(speed, pitchFactor)

/**
 * At most one value per [periodMs]: the first at once, then the latest of those that came meanwhile once the period is
 * over, so a burst always ends on its last value.
 */
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    conflate().collect { value ->
        emit(value)
        delay(periodMs)
    }
}

/**
 * Keeps [player]'s speed and pitch on the stored [tempo]: the stored value as soon as it is read, then every change,
 * at most one per [throttleMs]. Collects on [scope], which must run on the player's thread. The parameters are the
 * player's, not an item's, so they hold across the queue, gapless transitions included.
 */
internal fun followTempo(
    player: Player,
    tempo: Flow<PlaybackTempo>,
    scope: CoroutineScope,
    throttleMs: Long = TEMPO_THROTTLE_MS,
): Job = scope.launch {
    tempo.throttleLatest(throttleMs).collect { player.playbackParameters = it.toPlaybackParameters() }
}
