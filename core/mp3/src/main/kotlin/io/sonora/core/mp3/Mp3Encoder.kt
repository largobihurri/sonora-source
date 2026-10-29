package io.sonora.core.mp3

/**
 * MP3 encoder backed by LAME 3.100 (`libsonora_mp3.so`). The output keeps the input's sample rate and channel count,
 * carries no ID3 tags (Sonora writes its own), and starts with a LAME/Xing info frame placeholder: after [flush],
 * overwrite the stream's first [lametagFrame]`.size` bytes with [lametagFrame] so players see the real length, the
 * seek table and the encoder delay. Holds a native encoder until [close]; not thread-safe.
 */
class Mp3Encoder private constructor(
    private val bridge: LameBridge,
    private var handle: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val mode: Mp3Mode,
) : AutoCloseable {

    private var flushed = false

    /** Encodes [frames] frames of interleaved 16-bit [pcm]; returns the MP3 bytes ready so far (often none). */
    fun encode(pcm: ShortArray, frames: Int = pcm.size / channels): ByteArray {
        val live = streamHandle()
        checkFrames(frames, pcm.size)
        val out = ByteArray(maxOutputBytes(frames))
        return bytes(out, bridge.encodeShorts(live, pcm, frames, out))
    }

    /** Encodes [frames] frames of interleaved float [pcm] (full scale is -1..1). */
    fun encode(pcm: FloatArray, frames: Int = pcm.size / channels): ByteArray {
        val live = streamHandle()
        checkFrames(frames, pcm.size)
        val out = ByteArray(maxOutputBytes(frames))
        return bytes(out, bridge.encodeFloats(live, pcm, frames, out))
    }

    /** Encodes what LAME still buffers and ends the stream; nothing can be encoded after it. */
    fun flush(): ByteArray {
        val live = streamHandle()
        flushed = true
        val out = ByteArray(FLUSH_BYTES)
        return bytes(out, bridge.flush(live, out))
    }

    /** The LAME/Xing info frame for the stream's first frame; call after [flush]. Empty when LAME wrote none. */
    fun lametagFrame(): ByteArray {
        val live = liveHandle()
        check(flushed) { "lametagFrame() needs flush() first" }
        val size = bridge.lametagFrame(live, ByteArray(0))
        if (size < 0) throw Mp3EncoderException.EncodeFailed(size)
        if (size == 0) return ByteArray(0)
        val frame = ByteArray(size)
        val written = bridge.lametagFrame(live, frame)
        if (written != size) throw Mp3EncoderException.EncodeFailed(if (written < 0) written else -1)
        return frame
    }

    /** Releases the native encoder; later calls to [close] do nothing, any other call fails. */
    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            bridge.destroy(current)
        }
    }

    private fun liveHandle(): Long {
        check(handle != 0L) { "Mp3Encoder is closed" }
        return handle
    }

    /** The handle of a stream that still takes input. */
    private fun streamHandle(): Long {
        val live = liveHandle()
        check(!flushed) { "Mp3Encoder is flushed: the stream has ended" }
        return live
    }

    private fun checkFrames(frames: Int, samples: Int) {
        require(frames >= 0 && frames.toLong() * channels <= samples) {
            "$frames frames of $channels channels do not fit in $samples samples"
        }
    }

    private fun bytes(out: ByteArray, written: Int): ByteArray {
        if (written < 0) throw Mp3EncoderException.EncodeFailed(written)
        return out.copyOf(written)
    }

    companion object {
        /** The sample rates MPEG-1, 2 and 2.5 layer III can carry. */
        val SAMPLE_RATES_HZ: Set<Int> = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

        private val MPEG1_KBPS = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MPEG2_KBPS = listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        /** LAME's MPEG-2.5 table (tables.c) stops at 64 kbps; it would silently lower anything above it. */
        private val MPEG25_KBPS = listOf(8, 16, 24, 32, 40, 48, 56, 64)

        /** lame.h: lame_encode_flush needs at least 7200 bytes. */
        private const val FLUSH_BYTES = 7_200

        /** lame.h's worst case for one encode call: 1.25 * samples per channel + 7200. */
        private fun maxOutputBytes(frames: Int): Int = (5L * frames + 3).div(4).toInt() + FLUSH_BYTES

        /**
         * The constant bitrates the MPEG version of [sampleRateHz] allows: MPEG-1 from 32 kHz up, MPEG-2 from 16 kHz,
         * MPEG-2.5 below.
         */
        fun cbrBitratesKbps(sampleRateHz: Int): List<Int> {
            require(sampleRateHz in SAMPLE_RATES_HZ) { "MP3 cannot carry $sampleRateHz Hz" }
            return when {
                sampleRateHz >= 32_000 -> MPEG1_KBPS
                sampleRateHz >= 16_000 -> MPEG2_KBPS
                else -> MPEG25_KBPS
            }
        }

        /**
         * @throws IllegalArgumentException for a sample rate outside [SAMPLE_RATES_HZ], other than 1 or 2 channels,
         *   or a CBR bitrate outside [cbrBitratesKbps].
         * @throws Mp3EncoderException.Unavailable when the native library is not available for this device's ABI.
         * @throws Mp3EncoderException.InitFailed when LAME rejects the settings.
         */
        fun open(sampleRateHz: Int, channels: Int, mode: Mp3Mode): Mp3Encoder =
            open(sampleRateHz, channels, mode) { LameJni }

        /** Validates before [bridge] is first touched, so bad arguments never load the native library. */
        internal fun open(sampleRateHz: Int, channels: Int, mode: Mp3Mode, bridge: () -> LameBridge): Mp3Encoder {
            val bitrates = cbrBitratesKbps(sampleRateHz)
            require(channels == 1 || channels == 2) { "MP3 carries 1 or 2 channels, not $channels" }
            val (cbrKbps, vbrQuality) = when (mode) {
                is Mp3Mode.Cbr -> {
                    require(mode.bitrateKbps in bitrates) {
                        "${mode.bitrateKbps} kbps is not an MP3 bitrate at $sampleRateHz Hz (one of $bitrates)"
                    }
                    mode.bitrateKbps to -1
                }
                is Mp3Mode.Vbr -> 0 to mode.quality
            }
            val handleOut = LongArray(1)
            val (native, status) = try {
                val native = bridge()
                native to native.create(sampleRateHz, channels, cbrKbps, vbrQuality, handleOut)
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError on first use, NoClassDefFoundError on later ones.
                throw Mp3EncoderException.Unavailable(e)
            }
            if (status != LameBridge.STATUS_OK) throw Mp3EncoderException.InitFailed(status)
            return Mp3Encoder(native, handleOut[0], sampleRateHz, channels, mode)
        }
    }
}
