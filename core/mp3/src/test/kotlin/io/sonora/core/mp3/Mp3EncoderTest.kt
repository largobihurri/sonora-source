package io.sonora.core.mp3

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp3EncoderTest {

    private val bridge = FakeLameBridge()

    private fun open(sampleRateHz: Int = 44_100, channels: Int = 2, mode: Mp3Mode = Mp3Mode.Cbr(320)) =
        Mp3Encoder.open(sampleRateHz, channels, mode) { bridge }

    // --- Settings ---

    @Test
    fun `the sample rates are those of MPEG-1, 2 and 2_5 layer III`() {
        assertThat(Mp3Encoder.SAMPLE_RATES_HZ)
            .containsExactly(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    }

    @Test
    fun `CBR bitrates follow the MPEG version of the sample rate`() {
        val mpeg1 = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val mpeg2 = listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        // LAME's MPEG-2.5 table (tables.c) stops at 64 kbps, and it silently lowers anything above it.
        val mpeg25 = listOf(8, 16, 24, 32, 40, 48, 56, 64)
        for (rate in listOf(32_000, 44_100, 48_000)) {
            assertThat(Mp3Encoder.cbrBitratesKbps(rate)).containsExactlyElementsIn(mpeg1).inOrder()
        }
        for (rate in listOf(16_000, 22_050, 24_000)) {
            assertThat(Mp3Encoder.cbrBitratesKbps(rate)).containsExactlyElementsIn(mpeg2).inOrder()
        }
        for (rate in listOf(8_000, 11_025, 12_000)) {
            assertThat(Mp3Encoder.cbrBitratesKbps(rate)).containsExactlyElementsIn(mpeg25).inOrder()
        }
        assertThrows(IllegalArgumentException::class.java) { Mp3Encoder.cbrBitratesKbps(96_000) }
    }

    @Test
    fun `VBR quality runs from 0 to 9`() {
        assertThat(Mp3Mode.Vbr(0).quality).isEqualTo(0)
        assertThat(Mp3Mode.Vbr(9).quality).isEqualTo(9)
        for (quality in listOf(-1, 10)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { Mp3Mode.Vbr(quality) }
            assertThat(failure).hasMessageThat().contains("$quality")
        }
    }

    @Test
    fun `open rejects a sample rate MP3 cannot carry without touching the native encoder`() {
        for (rate in listOf(0, -44_100, 44_000, 88_200, 96_000)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { open(sampleRateHz = rate) }
            assertThat(failure).hasMessageThat().contains("$rate")
        }
        assertThat(bridge.creates).isEmpty()
    }

    @Test
    fun `open rejects channel counts other than mono and stereo`() {
        for (channels in listOf(0, 3, 6)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { open(channels = channels) }
            assertThat(failure).hasMessageThat().contains("$channels")
        }
        assertThat(bridge.creates).isEmpty()
    }

    @Test
    fun `open rejects a CBR bitrate the sample rate's MPEG version does not have`() {
        val cases = listOf(
            44_100 to 144, 44_100 to 0, 48_000 to 8, 22_050 to 320, 22_050 to 192, 32_000 to 333,
            11_025 to 128, 8_000 to 80, 12_000 to 160,
        )
        for ((rate, kbps) in cases) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                open(sampleRateHz = rate, mode = Mp3Mode.Cbr(kbps))
            }
            assertThat(failure).hasMessageThat().contains("$kbps")
        }
        assertThat(bridge.creates).isEmpty()
    }

    @Test
    fun `CBR settings reach the native encoder with no VBR quality`() {
        val encoder = open(sampleRateHz = 44_100, channels = 2, mode = Mp3Mode.Cbr(320))
        open(sampleRateHz = 22_050, channels = 1, mode = Mp3Mode.Cbr(144))
        open(sampleRateHz = 11_025, channels = 1, mode = Mp3Mode.Cbr(64))

        assertThat(bridge.creates).containsExactly(
            FakeLameBridge.Create(44_100, 2, 320, -1),
            FakeLameBridge.Create(22_050, 1, 144, -1),
            FakeLameBridge.Create(11_025, 1, 64, -1),
        ).inOrder()
        assertThat(encoder.sampleRateHz).isEqualTo(44_100)
        assertThat(encoder.channels).isEqualTo(2)
        assertThat(encoder.mode).isEqualTo(Mp3Mode.Cbr(320))
    }

    @Test
    fun `VBR settings reach the native encoder with no bitrate`() {
        val encoder = open(sampleRateHz = 48_000, channels = 2, mode = Mp3Mode.Vbr(0))
        open(sampleRateHz = 8_000, channels = 1, mode = Mp3Mode.Vbr(9))

        assertThat(bridge.creates).containsExactly(
            FakeLameBridge.Create(48_000, 2, 0, 0),
            FakeLameBridge.Create(8_000, 1, 0, 9),
        ).inOrder()
        assertThat(encoder.mode).isEqualTo(Mp3Mode.Vbr(0))
    }

    @Test
    fun `a native setup failure is a typed failure carrying LAME's status`() {
        bridge.createStatus = -2
        val failure = assertThrows(Mp3EncoderException.InitFailed::class.java) { open() }
        assertThat(failure.status).isEqualTo(-2)
        assertThat(failure).hasMessageThat().contains("out of memory")
        assertThat(bridge.destroyed).isEmpty()
    }

    @Test
    fun `a missing native library is a typed unavailable failure, also on later attempts`() {
        // On the JVM libsonora_mp3.so is absent, as on a device ABI the app ships no build for (32-bit ARM).
        repeat(2) {
            val failure = assertThrows(Mp3EncoderException.Unavailable::class.java) {
                Mp3Encoder.open(44_100, 2, Mp3Mode.Cbr(320))
            }
            assertThat(failure).hasMessageThat().contains("not available")
            assertThat(failure.cause).isInstanceOf(LinkageError::class.java)
        }
    }

    // --- Encoding ---

    @Test
    fun `encode returns exactly the bytes the native encoder wrote`() {
        val encoder = open()
        bridge.encodeResult = 5

        val bytes = encoder.encode(ShortArray(2 * 1_152))

        assertThat(bytes).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(bridge.encodes.single().kind).isEqualTo("short")
        assertThat(bridge.encodes.single().frames).isEqualTo(1_152)
    }

    @Test
    fun `encode gives LAME its worst-case output buffer`() {
        val encoder = open()

        encoder.encode(ShortArray(2 * 4_096), 4_096)
        encoder.encode(FloatArray(2 * 1_000), 999)

        // lame.h: mp3buf_size = 1.25 * samples per channel + 7200, rounded up.
        assertThat(bridge.encodes.map { it.outCapacity }).containsExactly(12_320, 8_449).inOrder()
    }

    @Test
    fun `float input goes to the float entry point with frames counted per channel`() {
        val mono = open(channels = 1)
        bridge.encodeResult = 3

        val bytes = mono.encode(FloatArray(441))

        assertThat(bytes).hasLength(3)
        assertThat(bridge.encodes.single()).isEqualTo(FakeLameBridge.Encode("float", 441, 441, 7_752))
    }

    @Test
    fun `encode rejects more frames than the array holds, or fewer than none`() {
        val encoder = open(channels = 2)
        for (frames in listOf(-1, 101)) {
            assertThrows(IllegalArgumentException::class.java) { encoder.encode(ShortArray(200), frames) }
            assertThrows(IllegalArgumentException::class.java) { encoder.encode(FloatArray(201), frames) }
        }
        assertThat(bridge.encodes).isEmpty()
    }

    @Test
    fun `an odd trailing sample of stereo input is not a frame`() {
        val encoder = open(channels = 2)

        encoder.encode(ShortArray(201))

        assertThat(bridge.encodes.single().frames).isEqualTo(100)
    }

    @Test
    fun `a native encode error is a typed failure carrying LAME's status`() {
        val encoder = open()
        bridge.encodeResult = -4

        val shorts = assertThrows(Mp3EncoderException.EncodeFailed::class.java) { encoder.encode(ShortArray(2)) }
        val floats = assertThrows(Mp3EncoderException.EncodeFailed::class.java) { encoder.encode(FloatArray(2)) }

        assertThat(shorts.status).isEqualTo(-4)
        assertThat(floats.status).isEqualTo(-4)
        assertThat(shorts).hasMessageThat().contains("psychoacoustic")
    }

    // --- End of stream ---

    @Test
    fun `flush returns the remaining bytes and ends the stream`() {
        val encoder = open()
        bridge.flushResult = 4

        val tail = encoder.flush()

        assertThat(tail).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(bridge.flushCapacities.single()).isAtLeast(7_200)
        assertThrows(IllegalStateException::class.java) { encoder.encode(ShortArray(2)) }
        assertThrows(IllegalStateException::class.java) { encoder.encode(FloatArray(2)) }
        assertThrows(IllegalStateException::class.java) { encoder.flush() }
        assertThat(bridge.encodes).isEmpty()
        assertThat(bridge.flushCapacities).hasSize(1)
    }

    @Test
    fun `a native flush error is a typed failure carrying LAME's status`() {
        val encoder = open()
        bridge.flushResult = -1

        val failure = assertThrows(Mp3EncoderException.EncodeFailed::class.java) { encoder.flush() }

        assertThat(failure.status).isEqualTo(-1)
        assertThat(failure).hasMessageThat().contains("buffer too small")
    }

    @Test
    fun `the info frame is read after flush`() {
        val encoder = open(mode = Mp3Mode.Vbr(0))
        bridge.tag = ByteArray(417) { it.toByte() }

        assertThrows(IllegalStateException::class.java) { encoder.lametagFrame() }
        encoder.flush()
        val frame = encoder.lametagFrame()

        assertThat(frame).isEqualTo(bridge.tag)
    }

    @Test
    fun `no info frame reads as an empty array`() {
        val encoder = open()
        encoder.flush()

        assertThat(encoder.lametagFrame()).isEmpty()
    }

    // --- Closing ---

    @Test
    fun `close releases the native encoder once and later calls fail`() {
        val encoder = open()

        encoder.close()
        encoder.close()

        assertThat(bridge.destroyed).containsExactly(FakeLameBridge.HANDLE)
        assertThrows(IllegalStateException::class.java) { encoder.encode(ShortArray(2)) }
        assertThrows(IllegalStateException::class.java) { encoder.encode(FloatArray(2)) }
        assertThrows(IllegalStateException::class.java) { encoder.flush() }
        assertThrows(IllegalStateException::class.java) { encoder.lametagFrame() }
        assertThat(bridge.encodes).isEmpty()
        assertThat(bridge.flushCapacities).isEmpty()
    }

    @Test
    fun `close after flush also releases the native encoder`() {
        open().use { it.flush() }

        assertThat(bridge.destroyed).containsExactly(FakeLameBridge.HANDLE)
    }
}
