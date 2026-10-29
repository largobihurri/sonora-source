package io.sonora.core.domain.playback.output

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BitPerfectPlannerTest {

    private fun option(rate: Int, encoding: OutputEncoding, channels: Int = 2, bitPerfect: Boolean = true) =
        MixerOption(rate, channels, encoding, bitPerfect)

    private fun usb(vararg options: MixerOption) = AudioOutputDevice(
        id = 7,
        kind = OutputDeviceKind.USB,
        productName = "DAC",
        sampleRates = emptyList(),
        encodings = emptyList(),
        channelCounts = emptyList(),
        mixerOptions = options.toList(),
        activeMixer = null,
    )

    private fun stream(pcm: SourcePcm, rate: Int = 48_000, channels: Int = 2) = StreamFormat(rate, channels, pcm)

    private fun planned(stream: StreamFormat, device: AudioOutputDevice) = BitPerfectPlanner.plan(stream, device)

    // What the sink hands the output for each kind of source.

    @Test
    fun `8 and 16-bit sources are handed over as 16-bit, 24-bit and float ones as float`() {
        assertThat(SourcePcm.INT8.sinkEncoding).isEqualTo(OutputEncoding.PCM_16)
        assertThat(SourcePcm.INT16.sinkEncoding).isEqualTo(OutputEncoding.PCM_16)
        assertThat(SourcePcm.INT24.sinkEncoding).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(SourcePcm.FLOAT32.sinkEncoding).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(SourcePcm.INT32.sinkEncoding).isEqualTo(OutputEncoding.PCM_FLOAT)
    }

    // Exact matches.

    @Test
    fun `a 16-bit source plays on the same rate in 16-bit with no conversion`() {
        val plan = planned(stream(SourcePcm.INT16), usb(option(48_000, OutputEncoding.PCM_16), option(48_000, OutputEncoding.PCM_24_PACKED)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Active(option(48_000, OutputEncoding.PCM_16), OutputEncoding.PCM_16))
        assertThat((plan as BitPerfectPlan.Active).needsConversion).isFalse()
    }

    @Test
    fun `a 24-bit source plays in 24-bit packed when the device takes it`() {
        val plan = planned(
            stream(SourcePcm.INT24, 96_000),
            usb(option(96_000, OutputEncoding.PCM_16), option(96_000, OutputEncoding.PCM_32), option(96_000, OutputEncoding.PCM_24_PACKED)),
        ) as BitPerfectPlan.Active

        assertThat(plan.mixer.encoding).isEqualTo(OutputEncoding.PCM_24_PACKED)
        assertThat(plan.sinkEncoding).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(plan.needsConversion).isTrue()
    }

    @Test
    fun `a float source needs a float mixer format and none is converted`() {
        val plan = planned(stream(SourcePcm.FLOAT32), usb(option(48_000, OutputEncoding.PCM_32), option(48_000, OutputEncoding.PCM_FLOAT))) as BitPerfectPlan.Active

        assertThat(plan.mixer.encoding).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(plan.needsConversion).isFalse()
    }

    // Widening is lossless, narrowing is not.

    @Test
    fun `a 16-bit source can be widened when the device has no 16-bit format`() {
        val plan = planned(stream(SourcePcm.INT16), usb(option(48_000, OutputEncoding.PCM_32), option(48_000, OutputEncoding.PCM_24_PACKED))) as BitPerfectPlan.Active

        assertThat(plan.mixer.encoding).isEqualTo(OutputEncoding.PCM_24_PACKED)
        assertThat(plan.sinkEncoding).isEqualTo(OutputEncoding.PCM_16)
        assertThat(plan.needsConversion).isTrue()
    }

    @Test
    fun `a 24-bit source is never narrowed to 16-bit`() {
        val plan = planned(stream(SourcePcm.INT24), usb(option(48_000, OutputEncoding.PCM_16)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.DEPTH_NOT_OFFERED))
    }

    @Test
    fun `a 24-bit source falls back to 32-bit, then float`() {
        val int32 = planned(stream(SourcePcm.INT24), usb(option(48_000, OutputEncoding.PCM_FLOAT), option(48_000, OutputEncoding.PCM_32))) as BitPerfectPlan.Active
        val float = planned(stream(SourcePcm.INT24), usb(option(48_000, OutputEncoding.PCM_FLOAT))) as BitPerfectPlan.Active

        assertThat(int32.mixer.encoding).isEqualTo(OutputEncoding.PCM_32)
        assertThat(float.mixer.encoding).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(float.needsConversion).isFalse()
    }

    @Test
    fun `a 32-bit integer source is not offered since the sink hands it over as float`() {
        val plan = planned(stream(SourcePcm.INT32), usb(option(48_000, OutputEncoding.PCM_32), option(48_000, OutputEncoding.PCM_FLOAT)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.SOURCE_NOT_SUPPORTED))
    }

    // The rate and the channels must be exact.

    @Test
    fun `the sample rate is never changed`() {
        val plan = planned(stream(SourcePcm.INT16, 44_100), usb(option(48_000, OutputEncoding.PCM_16), option(96_000, OutputEncoding.PCM_16)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.SAMPLE_RATE_NOT_OFFERED))
    }

    @Test
    fun `the channel count is never changed`() {
        val plan = planned(stream(SourcePcm.INT16, channels = 1), usb(option(48_000, OutputEncoding.PCM_16, channels = 2)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.CHANNELS_NOT_OFFERED))
    }

    @Test
    fun `only bit-perfect mixer formats count`() {
        val plan = planned(stream(SourcePcm.INT16), usb(option(48_000, OutputEncoding.PCM_16, bitPerfect = false)))

        assertThat(plan).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.NO_BIT_PERFECT_FORMATS))
    }

    @Test
    fun `a device with no mixer formats offers nothing`() {
        assertThat(planned(stream(SourcePcm.INT16), usb())).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.NO_BIT_PERFECT_FORMATS))
    }

    @Test
    fun `only USB devices are planned for`() {
        val speaker = usb(option(48_000, OutputEncoding.PCM_16)).copy(kind = OutputDeviceKind.SPEAKER)

        assertThat(planned(stream(SourcePcm.INT16), speaker)).isEqualTo(BitPerfectPlan.Unavailable(BitPerfectUnavailable.NOT_USB))
    }

    // Armed mode.

    @Test
    fun `the mode is armed only when switched on and a device offers bit-perfect`() {
        val offering = AudioOutputSnapshot(listOf(usb(option(48_000, OutputEncoding.PCM_16))), mixerApi = true)
        val none = AudioOutputSnapshot(listOf(usb()), mixerApi = true)

        assertThat(BitPerfectMode.armed(enabled = true, offering)).isTrue()
        assertThat(BitPerfectMode.armed(enabled = false, offering)).isFalse()
        assertThat(BitPerfectMode.armed(enabled = true, none)).isFalse()
        assertThat(BitPerfectMode.armed(enabled = true, AudioOutputSnapshot.EMPTY)).isFalse()
    }
}
