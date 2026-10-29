package io.sonora.core.transcoder.encoder

import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.dsp.TpdfQuantizer
import io.sonora.core.transcoder.ogg.OggOpusWriter
import io.sonora.core.transcoder.ogg.OpusHeaders
import io.sonora.core.transcoder.ogg.OpusStreamConfig
import java.io.BufferedOutputStream
import java.io.Closeable
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

/**
 * An Opus encoder as the Ogg stage needs it: whole 20 ms frames of 16-bit little-endian PCM at 48 kHz go in, packets
 * come out, possibly a few frames later. [MediaCodecOpusFrameCodec] is the platform implementation.
 */
interface OpusFrameCodec : Closeable {
    /** The pre-skip in 48 kHz samples once the codec has stated it (in its `OpusHead` or its codec delay). */
    val preSkip: Int?

    /**
     * Feeds [length] bytes of [pcm]: one whole frame, or nothing when [endOfStream] closes the stream (the last frame
     * may carry the flag too). Passes every packet that is ready to [sink]; at the end of the stream it waits for
     * the codec to flush all of them first.
     */
    fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit)
}

/** Opens the codec for one output; fails clearly when the device has no Opus encoder. */
fun interface OpusFrameCodecFactory {
    fun open(channels: Int, bitrateBps: Int): OpusFrameCodec
}

/** The device has no Opus encoder (or refuses the format): the file cannot be produced on it. */
class OpusEncoderUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Builds [OpusOggEncoder]s over codecs from [codecs]; the profile must state a bitrate and a 48 kHz rate. A device
 * without an Opus encoder fails the file as unsupported.
 */
class OpusEncoderFactory(private val codecs: OpusFrameCodecFactory = MediaCodecOpusFrameCodec) : AudioEncoderFactory {
    override fun create(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long): AudioEncoder {
        val bitrate = requireNotNull(plan.profile.bitrateBps) { "Profile ${plan.profile.id} states no Opus bitrate" }
        require(plan.targetSampleRateHz == OpusOggEncoder.SAMPLE_RATE_HZ) {
            "Opus is encoded at 48 kHz, not ${plan.targetSampleRateHz} Hz"
        }
        val codec = try {
            codecs.open(plan.targetChannels, bitrate)
        } catch (e: OpusEncoderUnavailableException) {
            throw UnsupportedProfileException(plan.profile, e.message, e)
        }
        return OpusOggEncoder(plan, output, codec, ditherSeed)
    }
}

/**
 * Ogg Opus output: float frames become 16-bit little-endian PCM, are cut into 20 ms frames (the last one padded with
 * silence), go through [codec], and the packets through an [OggOpusWriter] that streams to [output]. The tags are
 * given by the engine before the first frame ([metadataFirst]) because Ogg puts them ahead of the audio; the length
 * the stream declares is the number of frames given, so the padding and the codec's delay are trimmed on playback.
 */
class OpusOggEncoder(
    private val plan: ConversionPlan,
    output: SeekableByteChannel,
    private val codec: OpusFrameCodec,
    ditherSeed: Long,
) : AudioEncoder {

    private val channels = plan.targetChannels
    private val quantizer = TpdfQuantizer(ditherSeed, dither = plan.dither)
    private val out = BufferedOutputStream(Channels.newOutputStream(output), OUTPUT_BUFFER)
    private val serialNumber = (ditherSeed xor (ditherSeed ushr 32)).toInt()
    private val frameBytes = FRAME_SAMPLES * channels * 2
    private val frame = ByteArray(frameBytes)
    private var frameFill = 0
    private var scratch = ByteArray(0)
    private var tags: ByteArray? = null
    private var writer: OggOpusWriter? = null
    private var realSamples = 0L
    private val sink: (ByteArray) -> Unit = { packet -> writer().writePacket(packet) }

    override val metadataFirst: Boolean get() = true

    override fun begin(metadata: List<MetadataChunk>) {
        tags = metadata.firstOrNull { it.id == TAGS_CHUNK_ID }?.data
    }

    override fun write(samples: FloatArray, frames: Int) {
        val count = frames * channels
        if (scratch.size < count * 2) scratch = ByteArray(count * 2)
        quantizer.quantizeToLittleEndian(samples, count, scratch)
        var at = 0
        val total = count * 2
        while (at < total) {
            val n = minOf(frameBytes - frameFill, total - at)
            System.arraycopy(scratch, at, frame, frameFill, n)
            frameFill += n
            at += n
            if (frameFill == frameBytes) {
                codec.submit(frame, frameBytes, endOfStream = false, sink)
                frameFill = 0
            }
        }
        realSamples += frames
    }

    /** The Opus tags travel in [begin]; [metadata] is empty for this container. Returns the frames at 48 kHz. */
    override fun finish(metadata: List<MetadataChunk>): Long {
        if (frameFill > 0) {
            frame.fill(0, frameFill, frameBytes)
            codec.submit(frame, frameBytes, endOfStream = true, sink)
            frameFill = 0
        } else {
            codec.submit(frame, 0, endOfStream = true, sink)
        }
        writer().finish(realSamples)
        out.flush()
        return realSamples
    }

    override fun close() = codec.close()

    private fun writer(): OggOpusWriter = writer ?: OggOpusWriter(
        out = out,
        serialNumber = serialNumber,
        channels = channels,
        preSkip = codec.preSkip ?: OpusStreamConfig.DEFAULT_PRE_SKIP,
        inputSampleRateHz = plan.sourceSampleRateHz,
        tags = tags ?: OpusHeaders.tags("Sonora (${plan.profile.displayName})", emptyList()),
    ).also { writer = it }

    companion object {
        /** Id of the [MetadataChunk] that carries the complete `OpusTags` packet. */
        const val TAGS_CHUNK_ID = "OTAG"

        const val SAMPLE_RATE_HZ = 48_000

        /** The 20 ms frame the platform encoder takes, in samples per channel. */
        const val FRAME_SAMPLES = 960

        private const val OUTPUT_BUFFER = 32 * 1024
    }
}
