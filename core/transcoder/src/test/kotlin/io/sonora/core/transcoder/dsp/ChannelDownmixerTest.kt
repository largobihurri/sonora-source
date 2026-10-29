package io.sonora.core.transcoder.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelDownmixerTest {

    /** Downmixes one frame whose only non-zero channel is [channel]. */
    private fun only(sourceChannels: Int, channel: Int, target: Int = 2): FloatArray {
        val frame = FloatArray(sourceChannels).also { it[channel] = 1f }
        return FloatArray(target).also { ChannelDownmixer(sourceChannels, target).process(frame, 1, it) }
    }

    @Test
    fun `5_1 front channels go to their side, centre to both, LFE nowhere`() {
        // WAV / Android order: FL FR FC LFE BL BR.
        val fl = only(6, 0)
        val fr = only(6, 1)
        val fc = only(6, 2)
        val lfe = only(6, 3)
        val bl = only(6, 4)
        val br = only(6, 5)

        assertThat(fl[0]).isGreaterThan(0f)
        assertThat(fl[1]).isEqualTo(0f)
        assertThat(fr[0]).isEqualTo(0f)
        assertThat(fr[1]).isEqualTo(fl[0])
        assertThat(fc[0]).isEqualTo(fc[1])
        assertThat(fc[0]).isWithin(1e-6f).of(fl[0] * 0.70710677f)
        assertThat(lfe.toList()).containsExactly(0f, 0f)
        assertThat(bl[1]).isEqualTo(0f)
        assertThat(br[0]).isEqualTo(0f)
        assertThat(bl[0]).isWithin(1e-6f).of(fl[0] * 0.70710677f)
    }

    @Test
    fun `full scale in every channel never clips`() {
        for (channels in 3..12) {
            val frame = FloatArray(channels) { 1f }
            val out = FloatArray(2)
            ChannelDownmixer(channels, 2).process(frame, 1, out)
            // Gains sum to 1 per side; allow float rounding of that sum.
            assertThat(out[0]).isAtMost(1.000001f)
            assertThat(out[1]).isAtMost(1.000001f)
            assertThat(out[0]).isGreaterThan(0.99f)
        }
    }

    @Test
    fun `quad and 7_1 keep left and right apart`() {
        // Quad: FL FR BL BR. 7.1: FL FR FC LFE BL BR SL SR.
        assertThat(only(4, 2)[1]).isEqualTo(0f)
        assertThat(only(4, 3)[0]).isEqualTo(0f)
        assertThat(only(8, 6)[1]).isEqualTo(0f)
        assertThat(only(8, 7)[0]).isEqualTo(0f)
        assertThat(only(8, 3).toList()).containsExactly(0f, 0f)
    }

    @Test
    fun `many frames are processed and the target may be the source buffer`() {
        val frames = 3
        val buffer = FloatArray(frames * 6) { if (it % 6 == 0) 0.5f else 0f } // FL only
        val separate = FloatArray(frames * 2)
        ChannelDownmixer(6, 2).process(buffer.copyOf(), frames, separate)

        ChannelDownmixer(6, 2).process(buffer, frames, buffer)

        assertThat(buffer.copyOf(frames * 2)).isEqualTo(separate)
        assertThat(separate[2]).isGreaterThan(0f)
        assertThat(separate[3]).isEqualTo(0f)
    }

    @Test
    fun `downmix to mono averages the stereo downmix`() {
        val mono = only(2, 0, target = 1)

        assertThat(mono[0]).isEqualTo(0.5f)
    }

    @Test
    fun `only downmixing is supported`() {
        assertThrows(IllegalArgumentException::class.java) { ChannelDownmixer(1, 2) }
        assertThrows(IllegalArgumentException::class.java) { ChannelDownmixer(6, 3) }
    }
}
