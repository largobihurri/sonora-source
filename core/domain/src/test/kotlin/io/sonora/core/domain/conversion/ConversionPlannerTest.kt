package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversionPlannerTest {

    private val xdj = ConversionProfiles.XDJ_700

    private fun source(rate: Int, channels: Int = 2, format: PcmSampleFormat? = PcmSampleFormat.INT24) =
        PcmFormat(rate, channels, format, codec = "test", durationUs = null, totalFrames = null)

    private fun plan(rate: Int, channels: Int = 2, format: PcmSampleFormat? = PcmSampleFormat.INT24) =
        ConversionPlanner.plan(source(rate, channels, format), xdj)

    @Test
    fun `XDJ-700 is the only registered profile and the default`() {
        assertThat(ConversionProfiles.all).containsExactly(xdj)
        assertThat(ConversionProfiles.DEFAULT).isSameInstanceAs(xdj)
        assertThat(ConversionProfiles.byId("xdj-700")).isSameInstanceAs(xdj)
        assertThat(ConversionProfiles.byId("cdj-3000")).isNull()
    }

    @Test
    fun `XDJ-700 profile is AIFF 16-bit big-endian, max 48 kHz, TPDF, stereo, tags copied`() {
        assertThat(xdj.container).isEqualTo(OutputContainer.AIFF)
        assertThat(xdj.sampleFormat).isEqualTo(OutputSampleFormat.PCM_16_BIG_ENDIAN)
        assertThat(xdj.sampleFormat.bitsPerSample).isEqualTo(16)
        assertThat(xdj.sampleRate).isEqualTo(SampleRateRule(maxSampleRateHz = 48_000, resampleTargetHz = 44_100))
        assertThat(xdj.dither).isEqualTo(DitherPolicy.TPDF_WHEN_REDUCING_PRECISION)
        assertThat(xdj.channels).isEqualTo(ChannelPolicy(maxChannels = 2))
        assertThat(xdj.metadata).isEqualTo(MetadataPolicy.COPY_TAGS_AND_ARTWORK)
        assertThat(xdj.fileExtension).isEqualTo("aiff")
    }

    @Test
    fun `rates up to 48 kHz are kept`() {
        for (rate in listOf(8_000, 22_050, 32_000, 44_100, 48_000)) {
            val plan = plan(rate)
            assertThat(plan.targetSampleRateHz).isEqualTo(rate)
            assertThat(plan.resample).isFalse()
        }
    }

    @Test
    fun `rates above 48 kHz are resampled to 44_1 kHz`() {
        for (rate in listOf(48_001, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)) {
            val plan = plan(rate)
            assertThat(plan.sourceSampleRateHz).isEqualTo(rate)
            assertThat(plan.targetSampleRateHz).isEqualTo(44_100)
            assertThat(plan.resample).isTrue()
        }
    }

    @Test
    fun `16-bit and 8-bit integer sources at a kept rate need no dither`() {
        assertThat(plan(44_100, format = PcmSampleFormat.INT16).dither).isFalse()
        assertThat(plan(48_000, format = PcmSampleFormat.INT16).dither).isFalse()
        assertThat(plan(44_100, format = PcmSampleFormat.INT8).dither).isFalse()
    }

    @Test
    fun `sources with more precision than 16 bits are dithered`() {
        for (format in listOf(PcmSampleFormat.INT24, PcmSampleFormat.INT32, PcmSampleFormat.FLOAT32, PcmSampleFormat.FLOAT64)) {
            assertThat(plan(44_100, format = format).dither).isTrue()
        }
    }

    @Test
    fun `lossy or unknown sources are dithered`() {
        assertThat(plan(44_100, format = null).dither).isTrue()
    }

    @Test
    fun `a 16-bit source that is resampled or downmixed leaves the 16-bit grid and is dithered`() {
        assertThat(plan(96_000, format = PcmSampleFormat.INT16).dither).isTrue()
        assertThat(plan(176_400, format = PcmSampleFormat.INT16).dither).isTrue()
        assertThat(plan(44_100, channels = 6, format = PcmSampleFormat.INT16).dither).isTrue()
    }

    @Test
    fun `a profile without dither never dithers`() {
        val plain = xdj.copy(id = "plain", dither = DitherPolicy.NONE)

        assertThat(ConversionPlanner.plan(source(96_000, format = PcmSampleFormat.FLOAT32), plain).dither).isFalse()
    }

    @Test
    fun `mono and stereo keep their channels, more than two downmix to stereo`() {
        assertThat(plan(44_100, channels = 1).targetChannels).isEqualTo(1)
        assertThat(plan(44_100, channels = 1).downmix).isFalse()
        assertThat(plan(44_100, channels = 2).targetChannels).isEqualTo(2)
        assertThat(plan(44_100, channels = 2).downmix).isFalse()
        val surround = plan(48_000, channels = 6)
        assertThat(surround.sourceChannels).isEqualTo(6)
        assertThat(surround.targetChannels).isEqualTo(2)
        assertThat(surround.downmix).isTrue()
    }

    @Test
    fun `the plan targets the profile bit depth and keeps the profile`() {
        val plan = plan(192_000, channels = 6, format = PcmSampleFormat.INT24)

        assertThat(plan.profile).isSameInstanceAs(xdj)
        assertThat(plan.targetBitsPerSample).isEqualTo(16)
        assertThat(plan).isEqualTo(
            ConversionPlan(
                profile = xdj,
                sourceSampleRateHz = 192_000,
                targetSampleRateHz = 44_100,
                sourceChannels = 6,
                targetChannels = 2,
                targetBitsPerSample = 16,
                dither = true,
            ),
        )
    }

    @Test
    fun `an invalid source format is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { plan(0) }
        assertThrows(IllegalArgumentException::class.java) { plan(44_100, channels = 0) }
    }

    @Test
    fun `output names replace the source extension with the profile extension`() {
        assertThat(xdj.outputFileName("01 Track.flac")).isEqualTo("01 Track.aiff")
        assertThat(xdj.outputFileName("Mix.v2.m4a")).isEqualTo("Mix.v2.aiff")
        assertThat(xdj.outputFileName("Already.aiff")).isEqualTo("Already.aiff")
        assertThat(xdj.outputFileName("NoExtension")).isEqualTo("NoExtension.aiff")
        assertThat(xdj.outputFileName(".hidden")).isEqualTo(".hidden.aiff")
    }

    @Test
    fun `a sample rate rule must not resample above its own maximum`() {
        assertThrows(IllegalArgumentException::class.java) { SampleRateRule(maxSampleRateHz = 48_000, resampleTargetHz = 96_000) }
    }

    @Test
    fun `a fixed sample rate rule outputs its rate for every source rate`() {
        val rule = SampleRateRule.fixed(48_000)

        for (rate in listOf(8_000, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            assertThat(rule.targetFor(rate)).isEqualTo(48_000)
        }
    }

    @Test
    fun `XDJ-700 has no bitrate`() {
        assertThat(xdj.bitrateBps).isNull()
    }

    @Test
    fun `the watch profile is Ogg Opus at 192 kbps and a fixed 48 kHz, stereo, tags copied`() {
        val watch = ConversionProfiles.WATCH_OPUS

        assertThat(watch.container).isEqualTo(OutputContainer.OGG_OPUS)
        assertThat(watch.sampleFormat).isEqualTo(OutputSampleFormat.OPUS)
        assertThat(watch.sampleRate).isEqualTo(SampleRateRule.fixed(48_000))
        assertThat(watch.bitrateBps).isEqualTo(192_000)
        assertThat(watch.dither).isEqualTo(DitherPolicy.NONE)
        assertThat(watch.channels).isEqualTo(ChannelPolicy(maxChannels = 2))
        assertThat(watch.metadata).isEqualTo(MetadataPolicy.COPY_TAGS_AND_ARTWORK)
        assertThat(watch.fileExtension).isEqualTo("opus")
        assertThat(watch.outputFileName("01 Track.flac")).isEqualTo("01 Track.opus")
    }

    @Test
    fun `the watch profile is looked up by id but not listed in the converter`() {
        assertThat(ConversionProfiles.all).doesNotContain(ConversionProfiles.WATCH_OPUS)
        assertThat(ConversionProfiles.byId("watch-opus")).isSameInstanceAs(ConversionProfiles.WATCH_OPUS)
    }

    @Test
    fun `a 44_1 kHz source is resampled to 48 kHz for the watch and never dithered`() {
        val plan = ConversionPlanner.plan(source(44_100, format = PcmSampleFormat.INT16), ConversionProfiles.WATCH_OPUS)

        assertThat(plan.targetSampleRateHz).isEqualTo(48_000)
        assertThat(plan.resample).isTrue()
        assertThat(plan.dither).isFalse()
        assertThat(plan.targetBitsPerSample).isEqualTo(16)
    }

    @Test
    fun `a 48 kHz source is not resampled for the watch and surround is downmixed`() {
        val plan = ConversionPlanner.plan(source(48_000, channels = 6), ConversionProfiles.WATCH_OPUS)

        assertThat(plan.resample).isFalse()
        assertThat(plan.targetChannels).isEqualTo(2)
        assertThat(plan.downmix).isTrue()
    }

    @Test
    fun `a bitrate must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { ConversionProfiles.WATCH_OPUS.copy(bitrateBps = 0) }
    }
}
