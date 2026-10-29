package io.sonora.core.watchsync.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the watch asks of the phone. Commands name queue entries by index AND id (the id of a `QueueItem`, unique in the
 * playback session), so a command built from a picture of the queue that is a step behind does nothing instead of acting
 * on another song. They carry no file or URI: the phone only ever acts on its own queue.
 */
@Serializable
sealed interface RemoteCommand {

    /**
     * Keep pushing the phone's state to me. Sent when the remote opens and every [RemoteLimits.RENEW_MS]; each one is answered
     * with the state at once. [withQueue] adds the queue window to the pushes (the queue screen is open).
     */
    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val withQueue: Boolean = false) : RemoteCommand

    /** Stop pushing (the remote was closed). */
    @Serializable
    @SerialName("unsubscribe")
    data object Unsubscribe : RemoteCommand

    @Serializable
    @SerialName("set_playing")
    data class SetPlaying(val playing: Boolean) : RemoteCommand

    @Serializable
    @SerialName("next")
    data object Next : RemoteCommand

    @Serializable
    @SerialName("previous")
    data object Previous : RemoteCommand

    /**
     * Turn the phone's music volume by [steps] (negative lowers it), exactly as the crown was turned. The phone caps one
     * message and the rate of raises, so no sequence of messages can ramp the volume up on its own.
     */
    @Serializable
    @SerialName("volume")
    data class VolumeSteps(val steps: Int) : RemoteCommand

    /** Play the queue entry [id] at [index] now. */
    @Serializable
    @SerialName("jump")
    data class Jump(val index: Int, val id: Long) : RemoteCommand

    /** Take the queue entry [id] at [index] out of the queue (not the playing one). */
    @Serializable
    @SerialName("remove")
    data class Remove(val index: Int, val id: Long) : RemoteCommand

    /** Move the upcoming queue entry [id] at [index] one place; see [moveTarget]. */
    @Serializable
    @SerialName("move")
    data class Move(val index: Int, val id: Long, val direction: QueueDirection) : RemoteCommand
}

/** The playing song as the watch needs it. [id] is the queue entry's id. */
@Serializable
data class RemoteTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** One queue entry. [id] is what commands name it by. */
@Serializable
data class RemoteQueueItem(val id: Long, val title: String, val artist: String)

/**
 * The picture of the phone's playback the phone pushes to the watch: the only message in that direction. [positionMs] is the
 * position when the state was taken; the watch moves it on with its own clock while [isPlaying] (see [positionAt]).
 * [queue], when present, is the window that starts at the playing song ([queueIndex]) and goes on through the upcoming ones,
 * at most [RemoteLimits.MAX_QUEUE_ITEMS] of the [queueSize] entries.
 */
@Serializable
data class RemoteState(
    val track: RemoteTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** The phone's music volume as a step out of [volumeMax]; both 0 when unknown. */
    val volume: Int = 0,
    val volumeMax: Int = 0,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val hasNext: Boolean = false,
    val queue: List<RemoteQueueItem>? = null,
) {
    /** The volume as a whole percent, 0..100. */
    val volumePercent: Int get() = if (volumeMax <= 0) 0 else (volume.coerceIn(0, volumeMax) * 100 / volumeMax)

    /** The position [elapsedMs] after this state was taken: it advances only while playing and stays inside the song. */
    fun positionAt(elapsedMs: Long): Long {
        val current = track ?: return 0L
        val moved = if (isPlaying) positionMs + elapsedMs else positionMs
        return moved.coerceIn(0L, current.durationMs.coerceAtLeast(0L))
    }

    /** This state with the volume moved by [steps], inside its range: what the watch shows until the phone confirms. */
    fun withVolumeSteps(steps: Int): RemoteState =
        copy(volume = (volume.toLong() + steps).coerceIn(0L, volumeMax.coerceAtLeast(0).toLong()).toInt())
}
