package io.sonora.core.designsystem.translucent

/**
 * Whether this device's GPU keeps up with the translucency it draws, judged frame by frame from the GPU time of each
 * frame (FrameMetrics.GPU_DURATION, which every sampling tier has: they need Android 12). Two signs make the tier
 * step down (see [TranslucentTier.stepDown]): [SEVERE_FRAMES] frames of at least [SEVERE_MS] within the last
 * [WINDOW_FRAMES], the driver stalling (a OnePlus 7 Pro drew full translucency at a GPU p90 near 5 s, with context
 * timeouts); or, once the window is full, [HEAVY_SHARE] of it heavy, more than twice the frame budget and never under
 * [HEAVY_FLOOR_MS]. The first [SETTLE_FRAMES] after a start or a step down are not judged: they record layers and
 * compile shaders. Pure, so it is unit-tested.
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
     * One frame that took [gpuMs] of GPU time with a frame budget of [budgetMs] (1000 / refresh rate). True means
     * the tier should step down now; the policy then starts over for the frames of the lower tier.
     */
    fun frame(gpuMs: Double, budgetMs: Double): Boolean {
        if (settling > 0) {
            settling--
            return false
        }
        val slot = seen % WINDOW_FRAMES
        if (heavy[slot]) heavyCount--
        if (severe[slot]) severeCount--
        heavy[slot] = gpuMs > maxOf(2 * budgetMs, HEAVY_FLOOR_MS)
        severe[slot] = gpuMs >= SEVERE_MS
        if (heavy[slot]) heavyCount++
        if (severe[slot]) severeCount++
        seen++
        val stepDown = severeCount >= SEVERE_FRAMES ||
            (seen >= WINDOW_FRAMES && heavyCount >= WINDOW_FRAMES * HEAVY_SHARE)
        if (stepDown) restart()
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

        /** Heavy never means less GPU time than this, so a 120 Hz screen is not held to an 8 ms budget. */
        const val HEAVY_FLOOR_MS = 20.0
    }
}
