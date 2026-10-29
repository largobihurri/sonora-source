package io.sonora.core.playback.dub

import io.sonora.core.audiodsp.dub.EchoDivision
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.dubmodel.PreampWay
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The echo and preamp settings the audio thread plays with: one immutable snapshot of the stored setup and the clock's
 * tempo, published whole, so the audio thread reads it once per buffer and never sees half of a change.
 */
internal class DubParams private constructor(
    val bpm: Double,
    val division: EchoDivision,
    val feedback: Float,
    val returnLevel: Float,
    private val highPass: FloatArray,
    private val lowPass: FloatArray,
    private val gains: FloatArray,
    val dropWays: Int,
) {
    fun highPassHz(way: Int): Float = highPass[way]

    fun lowPassHz(way: Int): Float = lowPass[way]

    fun gain(way: Int): Float = gains[way]

    companion object {
        fun of(setup: DubSetup, bpm: Double): DubParams {
            val ways = setup.clamped().preamp
            return DubParams(
                bpm = if (bpm > 0.0 && bpm.isFinite()) bpm else DubStage.DEFAULT_BPM,
                division = setup.division.toEcho(),
                feedback = setup.clamped().feedback,
                returnLevel = setup.clamped().returnLevel,
                highPass = FloatArray(PreampWay.entries.size) { ways[PreampWay.entries[it]].highPassHz },
                lowPass = FloatArray(PreampWay.entries.size) { ways[PreampWay.entries[it]].lowPassHz },
                gains = FloatArray(PreampWay.entries.size) { ways[PreampWay.entries[it]].gain },
                dropWays = setup.dropWays.fold(0) { mask, way -> mask or (1 shl way.ordinal) },
            )
        }

        val DEFAULT: DubParams = of(DubSetup(), DubStage.DEFAULT_BPM)

        private fun DubDivision.toEcho(): EchoDivision = when (this) {
            DubDivision.SIXTEENTH -> EchoDivision.SIXTEENTH
            DubDivision.EIGHTH -> EchoDivision.EIGHTH
            DubDivision.DOTTED_EIGHTH -> EchoDivision.DOTTED_EIGHTH
            DubDivision.QUARTER -> EchoDivision.QUARTER
            DubDivision.DOTTED_QUARTER -> EchoDivision.DOTTED_QUARTER
            DubDivision.HALF -> EchoDivision.HALF
        }
    }
}

/** A pad edge, stamped with the touch's uptime in nanoseconds (the `System.nanoTime` clock). */
internal data class DubEvent(val pad: DubPadKind, val engaged: Boolean, val uptimeNanos: Long)

/** What the DUB sink tells its owner, from the playback thread. */
internal interface DubHubListener {
    /** The stream configured now is hosted, or bypassed and why. */
    fun onHosting(decision: DubHostingDecision)

    /** DUB STOP's tail is over (silent long enough, or the cap was reached): the player may pause now. */
    fun onTailDone()
}

/**
 * The meeting point of the DUB controller (main thread) and [DubAudioSink] (playback thread), modelled on the DJ loop's
 * hub: plain values the sink reads once per buffer through `@Volatile` fields, pad edges through a lock-free queue
 * (posting allocates on the main thread; polling allocates nothing), and "release all" as a generation counter that can
 * never be dropped. The sink publishes back what it hosts and its meters.
 */
internal class DubHub {
    /** DUB mode is on. Read at configure (hosting) and per buffer (pads are ignored while off). */
    @Volatile
    var enabled: Boolean = false

    /** The session plays on another device. */
    @Volatile
    var remoteRouted: Boolean = false

    @Volatile
    var params: DubParams = DubParams.DEFAULT

    @Volatile
    var filterX: Float = 0f

    @Volatile
    var filterY: Float = 0f

    /** The preamp's kill switches, a bit per way (bit 0 = way 1). */
    @Volatile
    var kills: Int = 0

    /** DUB STOP is ringing the tail out: the dry signal is faded out. */
    @Volatile
    var dryMuted: Boolean = false

    @Volatile
    var listener: DubHubListener? = null

    /** What the sink decided for the stream configured last. */
    @Volatile
    var hosting: DubHostingDecision = DubHostingDecision.Bypassed(null)
        private set

    /** The peak of the last buffer handed on (after the limiter), linear. */
    @Volatile
    var peak: Float = 0f

    /** The limiter's deepest gain reduction over the last buffer, in dB (0 or more). */
    @Volatile
    var gainReductionDb: Float = 0f

    private val events = ConcurrentLinkedQueue<DubEvent>()
    private val releases = AtomicInteger()

    val releaseGeneration: Int get() = releases.get()

    fun post(event: DubEvent) {
        events.offer(event)
    }

    fun poll(): DubEvent? = events.poll()

    /** Lets go of every pad, kill and the filter at the next buffer. */
    fun releaseAll() {
        events.clear()
        releases.incrementAndGet()
    }

    fun publishHosting(decision: DubHostingDecision) {
        if (decision == hosting) return
        hosting = decision
        listener?.onHosting(decision)
    }
}
