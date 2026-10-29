package io.sonora.core.playback.crossfade

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.sonora.core.domain.playback.CrossfadePolicy
import io.sonora.core.domain.playback.CrossfadeTrack
import io.sonora.core.playback.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays crossfades on the session's player ([main]) with a tail player built on demand by [buildTail]: the Android
 * side of [CrossfadeCoordinator], which holds the logic. Follows [main]'s events, asks [CrossfadePolicy] how long the
 * fade into the next entry is, and wakes the coordinator when it asks to be, on [scope] (the main thread).
 *
 * Why two players: Media3 has no crossfade, and its gapless pipeline writes one song after the other into a single
 * sink, so mixing them in an audio processor would have to hold back the outgoing tail and would shift every position
 * the player reports after it. A second player that only plays the outgoing tail leaves the session's player, its
 * queue, positions, notification and play counts exactly as they are; the price is a separate decoder for a few
 * seconds per transition.
 *
 * [main]'s fade level is [mainGain], applied at its sink, so its volume (which controllers see) never moves.
 */
internal class CrossfadeController(
    private val main: ExoPlayer,
    private val mainGain: FadeGain,
    private val buildTail: () -> ExoPlayer,
    private val scope: CoroutineScope,
) : Player.Listener, CrossfadeDeck {

    private val coordinator = CrossfadeCoordinator(this, SystemClock::elapsedRealtime)
    private var tail: ExoPlayer? = null
    private var seconds = 0
    private var wake: Job? = null
    private var updating = false
    private var updateAgain = false
    private var lastEntry: String? = null

    private val tailListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                coordinator.onTailEnded()
                refresh()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Crossfade tail failed", error)
            coordinator.onTailEnded()
            refresh()
        }
    }

    /** Starts following [main]. */
    fun attach() {
        lastEntry = main.currentMediaItem?.mediaId
        main.addListener(this)
        refresh()
    }

    /** The crossfade length (0 is off); applies from the next transition on. */
    fun setSeconds(seconds: Int) {
        this.seconds = seconds
        coordinator.quickFades = seconds > 0
        refresh()
    }

    /** Stops following [main], lets go of the tail player and puts [main] back at full level. */
    fun detach() {
        main.removeListener(this)
        wake?.cancel()
        wake = null
        coordinator.release()
        tail?.release()
        tail = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val entry = mediaItem?.mediaId
        // The same entry restated (a queue change that keeps it current) is no change of song.
        val restated = entry == lastEntry && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        lastEntry = entry
        if (!restated) {
            val manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            coordinator.onEntryChanged(manual)
        }
        refresh()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
            coordinator.onSeek()
        }
        refresh()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
            )
        ) {
            refresh()
        }
    }

    override fun prepareTail(positionMs: Long) {
        val item = main.currentMediaItem ?: return
        val player = tail ?: buildTail().also {
            it.addListener(tailListener)
            tail = it
        }
        player.volume = 0f
        player.playWhenReady = false
        player.setMediaItem(item, positionMs)
        player.prepare()
    }

    override val tailReady: Boolean
        get() = tail?.playbackState == Player.STATE_READY

    override fun playTail(mainPositionMs: Long) {
        val player = tail ?: return
        // Started late (a busy main thread): the tail catches up to where the main player is now.
        if (mainPositionMs - player.currentPosition > CATCH_UP_MS) player.seekTo(mainPositionMs)
        player.play()
    }

    override fun stopTail() {
        // The player is kept for the next crossfade; stopping it frees its decoder and audio track.
        tail?.run {
            stop()
            clearMediaItems()
        }
    }

    override fun setTailGain(gain: Float) {
        tail?.volume = gain
    }

    override fun setMainGain(gain: Float) {
        mainGain.gain = gain
    }

    override fun advanceMain(): Boolean {
        val next = main.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return false
        // The same song next (a repeated entry, repeat-all on one song) is restated, not reported as a change.
        val reported = main.getMediaItemAt(next).mediaId != lastEntry
        main.seekTo(next, 0L)
        return reported
    }

    /** Updates the coordinator now and wakes it again when it asks; never re-entered from its own player calls. */
    private fun refresh() {
        if (updating) {
            updateAgain = true
            return
        }
        updating = true
        var wait: Long?
        try {
            do {
                updateAgain = false
                wait = coordinator.update(state())
            } while (updateAgain)
        } finally {
            updating = false
        }
        wake?.cancel()
        wake = wait?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                wake = null
                refresh()
            }
        }
    }

    private fun state(): MainState {
        val item = main.currentMediaItem
        val durationMs = main.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val playing = main.playWhenReady &&
            main.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            main.playbackState != Player.STATE_IDLE && main.playbackState != Player.STATE_ENDED
        return MainState(item?.mediaId, main.currentPosition, durationMs, playing, plannedFadeMs(item, durationMs))
    }

    /** The crossfade from the current entry into the one that plays after it. */
    private fun plannedFadeMs(item: MediaItem?, durationMs: Long): Long {
        if (seconds <= 0 || item == null) return 0L
        val next = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return 0L
        val outgoing = item.toCrossfadeTrack(durationMs)
        val incoming = main.getMediaItemAt(next).toCrossfadeTrack(durationMs = null)
        return CrossfadePolicy.fadeMs(seconds, outgoing, incoming, repeatOne = main.repeatMode == Player.REPEAT_MODE_ONE)
    }

    /** The entry as the policy sees it; [durationMs], when the player knows it, wins over the tagged length. */
    private fun MediaItem.toCrossfadeTrack(durationMs: Long?): CrossfadeTrack {
        val track = MediaItemMapper.toTrack(this)
        return CrossfadeTrack(
            albumId = track.albumId,
            discNumber = track.discNumber,
            trackNumber = track.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 } ?: track.durationMs,
        )
    }

    private companion object {
        const val TAG = "SonoraCrossfade"

        /** How far behind the main player the tail may start before it seeks to catch up. */
        const val CATCH_UP_MS = 30L
    }
}
