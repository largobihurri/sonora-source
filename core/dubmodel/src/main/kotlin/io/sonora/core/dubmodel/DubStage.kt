package io.sonora.core.dubmodel

import kotlinx.coroutines.flow.StateFlow

/** The five performance pads of the DUB screen that act on the audio (TAP sets the clock instead, see [DubStage.tap]). */
enum class DubPadKind { THROW, CUT, DROP, FREEZE, RUNAWAY }

/** Why the DUB stage passes the audio through untouched (the DUB screen's banner says it). */
enum class DubBypass {
    /** Bit-perfect USB output is armed: nothing may touch the samples. */
    BIT_PERFECT,

    /** The session plays on another device: the stage is this phone's. */
    REMOTE_RECEIVER,

    /** The stream has more than two channels. */
    TOO_MANY_CHANNELS,

    /** The stream is not PCM the stage reads (compressed, 8-bit, a channel mapping). */
    UNSUPPORTED_FORMAT,
}

/** Where the echo's tempo comes from. */
enum class DubClockSource {
    /** The beat grid of the track playing. */
    GRID,

    /** The tempo tapped on TAP, which overrides the grid until it is cleared. */
    TAP,

    /** Neither: the echo runs at [DubStage.DEFAULT_BPM]. */
    NONE,
}

/**
 * What the DUB stage is doing: whether DUB mode is [enabled], whether the stage runs on the stream playing ([hosted]) or
 * why not ([bypass]), the echo's tempo and where it comes from, and whether DUB STOP is ringing the tail out
 * ([stopping]).
 */
data class DubStageState(
    val enabled: Boolean = false,
    val hosted: Boolean = false,
    val bypass: DubBypass? = null,
    val bpm: Double = DubStage.DEFAULT_BPM,
    val clockSource: DubClockSource = DubClockSource.NONE,
    val stopping: Boolean = false,
)

/** The stage's output meter: the [peak] of the last buffer (linear, 1 = full scale) and the limiter's [gainReductionDb]. */
data class DubMeters(val peak: Float = 0f, val gainReductionDb: Float = 0f)

/**
 * The DUB stage as the DUB screen drives it (the player's side implements it). Pad edges carry the touch's uptime in
 * nanoseconds (the same clock as `System.nanoTime`), so THROW can start from the audio heard when the finger landed.
 * Every call is cheap and safe from the main thread; nothing here is stored (the stored setup is [DubSetupRepository]).
 */
interface DubStage {
    val state: StateFlow<DubStageState>

    /** The output meter now; read it only while the screen shows it. */
    fun meters(): DubMeters

    fun setPad(pad: DubPadKind, engaged: Boolean, uptimeNanos: Long)

    /** The XY filter: [x] in -1..1 (low-pass, centre detent, high-pass), [y] in 0..1 (resonance). */
    fun setFilter(x: Float, y: Float)

    fun setKilled(way: PreampWay, killed: Boolean)

    /** One TAP at [uptimeNanos]: after enough taps the tapped tempo drives the echo. */
    fun tap(uptimeNanos: Long)

    /** Drops the tapped tempo: the echo follows the beat grid again. */
    fun clearTap()

    /**
     * DUB STOP: the dry signal fades out while the player keeps running, so the echo rings out; once the tail is
     * silent (or after a cap) the player pauses where STOP was pressed. A pause from anywhere else stays immediate.
     */
    fun stop()

    /** PLAY: during DUB STOP's ring out it brings the dry signal back; otherwise it starts the player. */
    fun play()

    /** Lets go of every pad, kill and the filter (the screen left, focus lost); the tails ring on. */
    fun releaseAll()

    companion object {
        /** The echo's tempo without a grid or a tap. */
        const val DEFAULT_BPM: Double = 120.0
    }
}
