package io.sonora.core.playback.clock

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.domain.playback.ClockReadFailure
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.LoopClockSource
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.playback.QueueMediaId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The playback clock the DJ screen draws by: the service's own local player (ExoPlayer, in the app's process, on the
 * main looper) read straight from the UI, every frame. Its `currentPosition` is the renderer's clock (the audio sink's
 * position plus the time since it was taken, scaled by the speed), with the AudioTrack's depth already accounted for,
 * which is the position being heard; the media controller's state the UI otherwise collects is 250 ms apart.
 *
 * While a DJ loop repeats, the position is the loop's own (it wraps from Out to In) and the sample says so: see
 * [LoopClockSource].
 *
 * The service [attach]es the local player (never the Cast player, whose position is a receiver's, not this phone's) as
 * it builds it and [detach]es it as it goes. A player may only be called on its application looper, the main thread
 * here, so [read] says no from any other thread (and the sample is left alone).
 */
@Singleton
class LivePlaybackClock @Inject constructor(private val loops: LoopClockSource) : PlaybackClock {

    /** A clock that never reports a loop. */
    constructor() : this(LoopClockSource.NONE)

    @Volatile
    private var player: Player? = null

    // Only touched on the player's looper, inside read (and cleared by detach, which runs there too).
    private var cachedItem: MediaItem? = null
    private var cachedItemId = ClockSample.NO_ITEM

    // Diagnostics only: a plain field, so a read off the main thread may set it unseen by the main thread.
    override var lastFailure: Int = ClockReadFailure.NO_PLAYER
        private set

    /** Reads [player] from now on (the service's local player). */
    fun attach(player: Player) {
        cachedItem = null
        this.player = player
    }

    /** Stops reading the player: [read] says no until another is attached. */
    fun detach() {
        player = null
        cachedItem = null
    }

    override fun read(into: ClockSample): Boolean {
        val player = player ?: return refused(ClockReadFailure.NO_PLAYER)
        if (Looper.myLooper() != player.applicationLooper) return refused(ClockReadFailure.WRONG_THREAD)
        val item = player.currentMediaItem ?: return refused(ClockReadFailure.NO_ITEM)
        if (item !== cachedItem) {
            // The media id is parsed once per item: this runs on every drawn frame.
            cachedItemId = QueueMediaId.parse(item.mediaId).itemId ?: ClockSample.NO_ITEM
            cachedItem = item
        }
        into.queueItemId = cachedItemId
        into.positionMs = player.currentPosition
        into.speed = player.playbackParameters.speed
        into.playing = player.isPlaying
        // The loop being heard, read in the same breath as the position it explains.
        loops.readInto(into)
        lastFailure = ClockReadFailure.NONE
        return true
    }

    private fun refused(reason: Int): Boolean {
        lastFailure = reason
        return false
    }
}
