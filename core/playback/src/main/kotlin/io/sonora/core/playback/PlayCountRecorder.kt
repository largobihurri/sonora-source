package io.sonora.core.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.sonora.core.domain.stats.Listening
import io.sonora.core.domain.stats.ListeningMeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Feeds the session player's entries and play state to a [PlayCountTracker] and hands each play that counts to
 * [record] (the song's library key). Only the playback service's player is followed, so conversions and exports,
 * which decode on their own, never count. Runs on [scope]'s thread, the player's (main) thread; a failed record is
 * logged and never stops playback.
 *
 * The song's length is taken whenever the player knows it for the current entry: on READY, but also at a gapless
 * transition (the next song was prepared ahead, so no READY follows) and when the timeline learns it later.
 *
 * Each playback's listening time is measured by [meter] and handed to [listened] whenever it pauses, counts or ends,
 * so the statistics keep the part of a skipped song that was heard.
 *
 * Plays and listening are written on [writes], which outlives [scope] (the service's, cancelled when it stops):
 * [detach] counts the play the service is stopping in, and a write under way still finishes. Each write gives up after
 * [RECORD_TIMEOUT_MS], so nothing is left running for long.
 */
internal class PlayCountRecorder(
    private val player: Player,
    private val tracker: PlayCountTracker,
    private val scope: CoroutineScope,
    private val writes: CoroutineScope,
    private val meter: ListeningMeter = ListeningMeter(SystemClock::elapsedRealtime, System::currentTimeMillis),
    private val listened: suspend (Listening) -> Unit = {},
    private val record: suspend (trackUri: String) -> Unit,
) : Player.Listener {

    private var check: Job? = null

    /** Starts following [player], from the entry it holds now. */
    fun attach() {
        player.addListener(this)
        tracker.setSpeed(player.playbackParameters.speed)
        startEntry(player.currentMediaItem, isRepeat = false)
    }

    /** Stops following [player] (the service is stopping) and records the current play if it counts by now. */
    fun detach() {
        player.removeListener(this)
        check?.cancel()
        check = null
        tracker.setPlaying(false)
        report(tracker.poll())
        save(meter.finish())
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        startEntry(mediaItem, isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        report(tracker.poll())
        tracker.setPlaying(isPlaying)
        save(meter.setPlaying(isPlaying))
        reschedule()
    }

    /** A new speed changes how fast the song is heard, so when the play could count. */
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        tracker.setSpeed(playbackParameters.speed)
        report(tracker.poll())
        reschedule()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) durationKnown()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = durationKnown()

    private fun startEntry(item: MediaItem?, isRepeat: Boolean) {
        val key = item?.let { (it.requestMetadata.mediaUri ?: it.localConfiguration?.uri)?.toString() }
        val durationMs = item?.mediaMetadata?.durationMs ?: 0L
        // The playback the tracker ends is still the meter's current one: a play it reports is marked there first.
        report(tracker.start(item?.mediaId, key, durationMs, player.isPlaying, isRepeat))
        save(meter.start(item?.mediaId, key, player.isPlaying, isRepeat))
        playerDurationMs()?.let(tracker::setDuration)
        reschedule()
    }

    /** The player may know the current entry's length now: it decides the threshold from here on. */
    private fun durationKnown() {
        playerDurationMs()?.let(tracker::setDuration)
        report(tracker.poll())
        reschedule()
    }

    private fun playerDurationMs(): Long? = player.duration.takeIf { it != C.TIME_UNSET }

    /** Checks again when the current playback could reach its threshold, if it keeps playing. */
    private fun reschedule() {
        check?.cancel()
        val waitMs = tracker.nextCheckInMs() ?: return
        check = scope.launch {
            delay(waitMs)
            check = null
            report(tracker.poll())
            reschedule()
        }
    }

    /** Records a play that counts, and saves its playback as counted. */
    private fun report(trackUri: String?) {
        if (trackUri == null) return
        save(meter.markCounted())
        write("Play") { record(trackUri) }
    }

    private fun save(listening: Listening?) {
        if (listening != null) write("Listening") { listened(listening) }
    }

    private fun write(what: String, block: suspend () -> Unit) {
        writes.launch {
            try {
                withTimeout(RECORD_TIMEOUT_MS) { block() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "$what not recorded in time", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what not recorded", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraPlays"
        const val RECORD_TIMEOUT_MS = 10_000L
    }
}
