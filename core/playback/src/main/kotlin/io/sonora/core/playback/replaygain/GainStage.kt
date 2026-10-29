package io.sonora.core.playback.replaygain

/**
 * The ReplayGain gain stage: one linear gain on interleaved float samples, the same on every channel. A new target
 * is reached linearly over [RAMP_SECONDS] from wherever the gain is (also mid-ramp), so moving from one song's gain
 * to the next, or a settings change, never clicks; an immediate target (a stream starting from silence) applies from
 * the first sample. Real time: it allocates nothing.
 */
class GainStage {
    private var sampleRate = 0
    private var current = 1.0
    private var target = 1.0
    private var rampFramesLeft = 0

    /** Whether the samples go through unchanged: the gain is 1 and is staying there. */
    val isUnity: Boolean get() = current == 1.0 && target == 1.0

    /** The stream's sample rate, which times the ramp; the gain carries on. */
    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    /** Heads for [gain]: over [RAMP_SECONDS], or at once when [immediate] (or before any stream is configured). */
    fun setTarget(gain: Double, immediate: Boolean = false) {
        if (immediate || sampleRate <= 0) {
            current = gain
            target = gain
            rampFramesLeft = 0
            return
        }
        if (gain == target) return
        target = gain
        rampFramesLeft = if (current == gain) 0 else (RAMP_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    }

    /** Back to unity, with no ramp. */
    fun reset() {
        current = 1.0
        target = 1.0
        rampFramesLeft = 0
    }

    /** Applies the gain to the first [frames] frames of [samples] ([channels] samples each), in place. */
    fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (isUnity) return
        var i = 0
        for (f in 0 until frames) {
            if (rampFramesLeft > 0) {
                current += (target - current) / rampFramesLeft
                rampFramesLeft--
                if (rampFramesLeft == 0) current = target
            }
            val gain = current.toFloat()
            for (ch in 0 until channels) {
                samples[i] *= gain
                i++
            }
        }
    }

    companion object {
        /** How long a change of gain takes. */
        const val RAMP_SECONDS = 0.03
    }
}
