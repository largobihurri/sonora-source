package io.sonora.core.playback.levels

import io.sonora.core.domain.playback.PlaybackBandLevels
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * The live band levels, shared by the player's audio sink (which writes them through [BandLevelMeter]) and the UI
 * (which [read]s them). The playback service runs in the app's process, so both sides hold this same singleton.
 *
 * **Sync.** The sink measures audio well before it is heard (its own buffer plus the AudioTrack's latency, a few
 * hundred ms, or seconds with a deep AudioTrack), so every level frame carries the media time it ends at, kept in a
 * ring of [CAPACITY] frames ([RING_SECONDS] of audio at one frame per [HOP_SECONDS]). The sink also reports the
 * position it is playing out ([reportPlayed], the same position the renderer uses as the player's clock, latency
 * included); between reports the position runs on with the monotonic clock while the sink plays. [read] hands out the
 * newest frame at or before that position: what the speaker is playing now.
 *
 * **Threads.** The playback thread writes frames and positions; the UI reads them on every drawn frame. Both are a
 * few dozen field accesses, so a plain lock (uncontended in practice, no allocation) keeps them consistent.
 */
@Singleton
class LiveBandLevels internal constructor(private val nanoTime: () -> Long) : PlaybackBandLevels {

    @Inject
    constructor() : this(System::nanoTime)

    private val watchers = AtomicInteger()

    /** Whether anyone watches; [read] gives nothing while this is false (the meter measures regardless). */
    internal val watching: Boolean get() = watchers.get() > 0

    private val lock = Any()
    private val times = LongArray(CAPACITY)
    private val values = FloatArray(CAPACITY * BANDS)

    /** Frames published since the last clear; the newest sits at `(written - 1) % CAPACITY`. */
    private var written = 0L

    private var playedUs = 0L
    private var playedAtNanos = NEVER
    private var running = false

    override fun watch(): AutoCloseable {
        watchers.incrementAndGet()
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) watchers.decrementAndGet() }
    }

    override fun read(into: FloatArray): Boolean {
        if (!watching) return false
        synchronized(lock) {
            if (playedAtNanos == NEVER || written == 0L) return false
            val sinceReport = nanoTime() - playedAtNanos
            if (sinceReport > STALE_REPORT_NANOS) return false
            val position = playedUs + if (running) sinceReport / 1_000 else 0L
            var frame = written - 1
            val oldest = maxOf(0L, written - CAPACITY)
            while (frame >= oldest) {
                val slot = (frame % CAPACITY).toInt()
                if (times[slot] <= position) {
                    if (position - times[slot] > STALE_FRAME_US) return false
                    System.arraycopy(values, slot * BANDS, into, 0, BANDS)
                    return true
                }
                frame--
            }
            return false
        }
    }

    /** Stores the levels of the frame ending at media time [endUs]. */
    internal fun publish(endUs: Long, levels: FloatArray) {
        synchronized(lock) {
            val slot = (written % CAPACITY).toInt()
            times[slot] = endUs
            System.arraycopy(levels, 0, values, slot * BANDS, BANDS)
            written++
        }
    }

    /** Forgets every frame and the played position: the stream restarted, jumped or stopped being measured. */
    internal fun clearFrames() {
        synchronized(lock) {
            written = 0L
            playedAtNanos = NEVER
        }
    }

    /** The sink is playing out media time [positionUs] now. */
    internal fun reportPlayed(positionUs: Long) {
        synchronized(lock) {
            playedUs = positionUs
            playedAtNanos = nanoTime()
        }
    }

    /** Whether the sink is playing (its position moves on between reports) or paused. */
    internal fun setRunning(running: Boolean) {
        synchronized(lock) {
            if (running == this.running) return
            // Re-anchors the position, so a pause freezes it where it was and a resume carries on from there.
            if (playedAtNanos != NEVER) {
                val now = nanoTime()
                if (this.running) playedUs += (now - playedAtNanos) / 1_000
                playedAtNanos = now
            }
            this.running = running
        }
    }

    /** The newest frame's end time, its levels copied into [into]; `null` with none. For tests. */
    internal fun newestFrame(into: FloatArray): Long? = synchronized(lock) {
        if (written == 0L) return null
        val slot = ((written - 1) % CAPACITY).toInt()
        System.arraycopy(values, slot * BANDS, into, 0, BANDS)
        times[slot]
    }

    internal companion object {
        /** Audio per level frame: about one display frame at 60 Hz (the meter's hop). */
        const val HOP_SECONDS = 0.016

        /**
         * How much audio the ring spans. The sink can queue seconds of audio ahead of the speaker (Media3 sizes the
         * AudioTrack for the fastest playback speed it allows), and the frame being heard must still be in the ring.
         */
        const val RING_SECONDS = 10.0

        /** Frames kept: [RING_SECONDS] of them, well over any plausible queue. */
        val CAPACITY = ceil(RING_SECONDS / HOP_SECONDS).toInt()

        private val BANDS = PlaybackBandLevels.BAND_COUNT
        private const val NEVER = Long.MIN_VALUE

        /** A position older than this means the sink stopped reporting (stopped, stalled or gone): no live levels. */
        private const val STALE_REPORT_NANOS = 500_000_000L

        /** A frame this far behind the played position is not what is heard (the meter stopped hearing the stream). */
        private const val STALE_FRAME_US = 100_000L
    }
}
