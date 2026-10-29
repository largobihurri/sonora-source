package io.sonora.core.transcoder.encoder

import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.dsp.TpdfQuantizer
import io.sonora.core.transcoder.flac.FlacStreamInfo
import io.sonora.core.transcoder.flac.FlacStreamWriter
import io.sonora.core.transcoder.ogg.OpusHeaders
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/**
 * A FLAC encoder as [FlacEncoder] needs it: interleaved PCM goes in, encoded FLAC frames come out, possibly a block
 * later. [MediaCodecFlacFrameCodec] is the platform implementation.
 */
interface FlacFrameCodec : Closeable {
    /** Whether [submit] takes 32-bit floats in native byte order (24-bit output) instead of 16-bit little-endian integers. */
    val floatInput: Boolean

    /** The STREAMINFO body (34 bytes) once the codec has stated its header. */
    val streamInfo: ByteArray?

    /**
     * Feeds [length] bytes of whole frames from [pcm] (nothing when [endOfStream] closes the stream; the last block may
     * carry the flag too) and passes every encoded frame that is ready to [sink]; at the end of the stream it waits for
     * the codec to flush all of them first.
     */
    fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit)
}

/** Opens the codec for one output; fails with [FlacEncoderUnavailableException] when the device cannot encode it. */
fun interface FlacFrameCodecFactory {
    fun open(sampleRateHz: Int, channels: Int, bitsPerSample: Int): FlacFrameCodec
}

/** The device has no FLAC encoder, or none that takes these settings (24-bit input, this rate). */
class FlacEncoderUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Builds [FlacEncoder]s over codecs from [codecs]; a device that cannot encode the plan fails the file as unsupported. */
class FlacEncoderFactory(private val codecs: FlacFrameCodecFactory = MediaCodecFlacFrameCodec) : AudioEncoderFactory {
    override fun create(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long): AudioEncoder {
        val bits = plan.targetBitsPerSample
        val codec = try {
            codecs.open(plan.targetSampleRateHz, plan.targetChannels, bits)
        } catch (e: FlacEncoderUnavailableException) {
            throw UnsupportedProfileException(plan.profile, e.message, e)
        }
        if (bits == 24 && !codec.floatInput) {
            codec.close()
            throw UnsupportedProfileException(plan.profile, "the FLAC encoder takes no 24-bit input")
        }
        return FlacEncoder(plan, output, codec, ditherSeed)
    }
}

/**
 * Native FLAC output: float frames are quantised to the plan's 16 or 24 bits (TPDF-dithered when the plan says so),
 * fed to [codec] in blocks, and its frames written behind Sonora's own metadata blocks ([FlacStreamWriter]): the
 * codec's STREAMINFO with the real length, the tags and the full-size cover, which the engine gives before the first
 * frame ([metadataFirst]). A codec whose STREAMINFO states another bit depth, rate or channel count than the plan
 * fails the file as unsupported rather than writing something else.
 */
class FlacEncoder(
    private val plan: ConversionPlan,
    private val output: SeekableByteChannel,
    private val codec: FlacFrameCodec,
    ditherSeed: Long,
) : AudioEncoder {

    private val channels = plan.targetChannels
    private val bits = plan.targetBitsPerSample
    private val quantizer = TpdfQuantizer(ditherSeed, dither = plan.dither, bits = bits)
    private val bytesPerSample = if (codec.floatInput) 4 else 2
    private val block = ByteBuffer.allocate(BLOCK_FRAMES * channels * bytesPerSample)
        .order(if (codec.floatInput) ByteOrder.nativeOrder() else ByteOrder.LITTLE_ENDIAN)
    private var shorts = ByteArray(0)
    private var floats = FloatArray(0)
    private var writer: FlacStreamWriter? = null
    private var vorbisComment: ByteArray? = null
    private var picture: ByteArray? = null
    private var checked = false
    private var realFrames = 0L
    private val sink: (ByteArray) -> Unit = { frame -> writer().writeFrame(frame) }

    override val metadataFirst: Boolean get() = true

    override fun begin(metadata: List<MetadataChunk>) {
        vorbisComment = metadata.firstOrNull { it.id == VORBIS_COMMENT_CHUNK_ID }?.data
        picture = metadata.firstOrNull { it.id == PICTURE_CHUNK_ID }?.data
        writer()
    }

    override fun write(samples: FloatArray, frames: Int) {
        val count = frames * channels
        var at = 0
        while (at < count) {
            val n = minOf(count - at, block.remaining() / bytesPerSample)
            if (codec.floatInput) {
                if (floats.size < n) floats = FloatArray(n)
                quantizer.quantizeToFloat(samples, n, floats, from = at)
                for (i in 0 until n) block.putFloat(floats[i])
            } else {
                if (shorts.size < n * 2) shorts = ByteArray(n * 2)
                quantizer.quantizeToLittleEndian(samples, n, shorts, from = at)
                block.put(shorts, 0, n * 2)
            }
            at += n
            if (!block.hasRemaining()) submit(endOfStream = false)
        }
        realFrames += frames
    }

    /** The tags travel in [begin]; [metadata] is empty for this container. Returns the frames written. */
    override fun finish(metadata: List<MetadataChunk>): Long {
        submit(endOfStream = true)
        val info = codec.streamInfo ?: throw IOException("The FLAC encoder stated no STREAMINFO")
        check(info)
        writer().finish(FlacStreamInfo(info), realFrames)
        return realFrames
    }

    override fun close() = codec.close()

    private fun submit(endOfStream: Boolean) {
        codec.submit(block.array(), block.position(), endOfStream, sink)
        block.clear()
        codec.streamInfo?.let { if (!checked) check(it) }
    }

    /** Fails the file when the codec encodes something other than the plan: never a silent 16-bit file. */
    private fun check(streamInfo: ByteArray) {
        checked = true
        val info = FlacStreamInfo(streamInfo)
        if (info.bitsPerSample != bits) {
            throw UnsupportedProfileException(plan.profile, "the FLAC encoder writes ${info.bitsPerSample}-bit, not $bits-bit samples")
        }
        if (info.sampleRateHz != plan.targetSampleRateHz || info.channels != channels) {
            throw UnsupportedProfileException(
                plan.profile,
                "the FLAC encoder writes ${info.sampleRateHz} Hz x ${info.channels}, not ${plan.targetSampleRateHz} Hz x $channels",
            )
        }
    }

    private fun writer(): FlacStreamWriter = writer ?: FlacStreamWriter(
        output,
        vorbisComment = vorbisComment ?: OpusHeaders.comments(vendor(plan), emptyList()),
        picture = picture,
    ).also { writer = it }

    companion object {
        /** Id of the [MetadataChunk] that carries the `VORBIS_COMMENT` block body. */
        const val VORBIS_COMMENT_CHUNK_ID = "FVCM"

        /** Id of the [MetadataChunk] that carries the `PICTURE` block body. */
        const val PICTURE_CHUNK_ID = "FPIC"

        /** Frames per block handed to the codec: the FLAC encoder's own default block size. */
        private const val BLOCK_FRAMES = 4_096

        /** The vendor string of the tags, e.g. "Sonora (FLAC)". */
        fun vendor(plan: ConversionPlan): String = "Sonora (${plan.profile.displayName})"
    }
}
