package io.sonora.core.designsystem.translucent

/**
 * Whether this device's GPU keeps up with the translucency it draws, judged frame by frame from the GPU time of each
 * frame (FrameMetrics.GPU_DURATION, which every sampling tier has: they need Android 12). Two signs make the tier
 * step down (see [TranslucentTier.stepDown]): [SEVERE_FRAMES] frames of at least [SEVERE_MS] within the last
 * [WINDOW_FRAMES], the driver stalling (a OnePlus 7 Pro drew full translucency at a GPU p90 near 5 s, with context
 * timeouts); or, once the window is full, [HEAVY_SHARE] of it heavy, more than twice the frame budget and more than
 * [HEAVY_FLOOR_MS] (two missed 60 Hz frames), so a fast screen whose animations only jank (a 144 Hz tablet at 16-30 ms
 * of GPU opening a panel) keeps its translucency. [frame] names the rule that fired ([StepDownRule]). The first
 * [SETTLE_FRAMES] after a start or a step down are not judged: they record layers and compile shaders. Pure, so it is
 * unit-tested.
 */
class TranslucentLoadPolicy {
    private val heavy = BooleanArray(WINDOW_FRAMES)
    private val severe = BooleanArray(WINDOW_FRAMES)
    private var seen = 0
    private var heavyCount = 0
    private var severeCount = 0
    private var settling = SETTLE_FRAMES

    /** Forget every frame, e.g. when the tier changed or monitoring starts again. */
    fun restart() {
        heavy.fill(false)
        severe.fill(false)
        seen = 0
        heavyCount = 0
        severeCount = 0
        settling = SETTLE_FRAMES
    }

    /**
     * One frame that took [gpuMs] of GPU time with a frame budget of [budgetMs] (1000 / refresh rate). A rule means
     * the tier should step down now, for that reason (a stall wins when both fire); the policy then starts over for
     * the frames of the lower tier. Null keeps the tier.
     */
    fun frame(gpuMs: Double, budgetMs: Double): StepDownRule? {
        if (settling > 0) {
            settling--
            return null
        }
        val slot = seen % WINDOW_FRAMES
        if (heavy[slot]) heavyCount--
        if (severe[slot]) severeCount--
        heavy[slot] = gpuMs > maxOf(2 * budgetMs, HEAVY_FLOOR_MS)
        severe[slot] = gpuMs >= SEVERE_MS
        if (heavy[slot]) heavyCount++
        if (severe[slot]) severeCount++
        seen++
        val stepDown = when {
            severeCount >= SEVERE_FRAMES -> StepDownRule.SEVERE
            seen >= WINDOW_FRAMES && heavyCount >= WINDOW_FRAMES * HEAVY_SHARE -> StepDownRule.HEAVY
            else -> null
        }
        if (stepDown != null) restart()
        return stepDown
    }

    companion object {
        /** Frames not judged after a start: layer recording and shader compilation. */
        const val SETTLE_FRAMES = 30

        /** The frames judged together: one to two seconds of animation. */
        const val WINDOW_FRAMES = 120

        /** A frame the GPU took this long on is a stall, not jank. */
        const val SEVERE_MS = 500.0

        /** Stalls within the window that step down. */
        const val SEVERE_FRAMES = 3

        /** The share of heavy frames in a full window that steps down. */
        const val HEAVY_SHARE = 0.25

        /**
         * Heavy always means more GPU time than this, two 60 Hz frames, whatever the refresh rate: a 120 or 144 Hz
         * screen is not held to its 8 or 7 ms budget, since a frame under it misses at most one 60 Hz frame.
         */
        const val HEAVY_FLOOR_MS = 2 * 1000.0 / 60
    }
}

/** Why [TranslucentLoadPolicy.frame] stepped the tier down, named in the log line. */
enum class StepDownRule {
    /** [TranslucentLoadPolicy.HEAVY_SHARE] of a full window was heavy. */
    HEAVY,

    /** [TranslucentLoadPolicy.SEVERE_FRAMES] stalls within the window. */
    SEVERE,
}
