package io.sonora.core.transcoder.encoder

import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.mp3.Mp3Encoder
import io.sonora.core.mp3.Mp3EncoderException
import io.sonora.core.mp3.Mp3Mode
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** An MP3 stream as [Mp3FileEncoder] needs it; [LameMp3Stream] over core:mp3's LAME in the app, a fake in tests. */
interface Mp3Stream : AutoCloseable {
    /** Encodes [frames] interleaved float frames of [pcm]; returns the MP3 bytes ready so far. */
    fun encode(pcm: FloatArray, frames: Int): ByteArray

    /** Ends the stream and returns its last bytes. */
    fun flush(): ByteArray

    /** The LAME/Xing info frame to write over the stream's first frame after [flush]; empty when there is none. */
    fun lametagFrame(): ByteArray
}

/** Opens an [Mp3Stream]; fails with [Mp3StreamUnavailableException] when this device cannot encode MP3 at all. */
fun interface Mp3StreamFactory {
    fun open(sampleRateHz: Int, channels: Int, mode: Mp3Mode): Mp3Stream
}

/** LAME is not there for this device's ABI, or refused the settings. */
class Mp3StreamUnavailableException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

/** [Mp3Stream] over core:mp3's [Mp3Encoder]. */
class LameMp3Stream private constructor(private val encoder: Mp3Encoder) : Mp3Stream {
    override fun encode(pcm: FloatArray, frames: Int): ByteArray = encoder.encode(pcm, frames)
    override fun flush(): ByteArray = encoder.flush()
    override fun lametagFrame(): ByteArray = encoder.lametagFrame()
    override fun close() = encoder.close()

    companion object Factory : Mp3StreamFactory {
        override fun open(sampleRateHz: Int, channels: Int, mode: Mp3Mode): Mp3Stream = try {
            LameMp3Stream(Mp3Encoder.open(sampleRateHz, channels, mode))
        } catch (e: Mp3EncoderException) {
            throw Mp3StreamUnavailableException(e.message ?: "MP3 encoder failed", e)
        }
    }
}

/**
 * Builds [Mp3FileEncoder]s: CBR at the profile's bitrate or VBR at its quality. A rate MP3 cannot carry, a bitrate
 * its MPEG version lacks, or a device without LAME fails the file as unsupported, checked before LAME is loaded.
 */
class Mp3EncoderFactory(private val streams: Mp3StreamFactory = LameMp3Stream) : AudioEncoderFactory {
    override fun create(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long): AudioEncoder {
        val profile = plan.profile
        val rate = plan.targetSampleRateHz
        if (rate !in Mp3Encoder.SAMPLE_RATES_HZ) {
            throw UnsupportedProfileException(profile, "MP3 cannot carry $rate Hz (one of ${Mp3Encoder.SAMPLE_RATES_HZ.sorted()})")
        }
        val mode = profile.vbrQuality?.let { Mp3Mode.Vbr(it) } ?: run {
            val kbps = requireNotNull(profile.bitrateBps) { "Profile ${profile.id} states no MP3 bitrate or quality" } / 1_000
            val allowed = Mp3Encoder.cbrBitratesKbps(rate)
            if (kbps !in allowed) throw UnsupportedProfileException(profile, "MP3 at $rate Hz has no $kbps kbps (one of $allowed)")
            Mp3Mode.Cbr(kbps)
        }
        val stream = try {
            streams.open(rate, plan.targetChannels, mode)
        } catch (e: Mp3StreamUnavailableException) {
            throw UnsupportedProfileException(profile, e.message, e)
        }
        return Mp3FileEncoder(plan, output, stream)
    }
}

/**
 * MP3 output: the ID3v2.3 tag first (the engine gives it before the audio, [metadataFirst]), then LAME's stream;
 * after the flush the LAME/Xing info frame goes over the stream's first frame, so players see the real length, the
 * seek table and the encoder delay. LAME takes the float frames as they are (lossy profiles do not dither); the
 * stream is closed in [close], also after a failure.
 */
class Mp3FileEncoder(
    plan: ConversionPlan,
    private val output: SeekableByteChannel,
    private val stream: Mp3Stream,
) : AudioEncoder {

    private val channels = plan.targetChannels
    private var audioStart = output.position()
    private var frames = 0L

    override val metadataFirst: Boolean get() = true

    override fun begin(metadata: List<MetadataChunk>) {
        metadata.firstOrNull { it.id == Id3MetadataChunkSource.MP3_ID3_CHUNK }?.let { writeFully(it.data) }
        audioStart = output.position()
    }

    override fun write(samples: FloatArray, frames: Int) {
        require(frames.toLong() * channels <= samples.size) { "$frames frames do not fit ${samples.size} samples" }
        writeFully(stream.encode(samples, frames))
        this.frames += frames
    }

    /** The tag travels in [begin]; [metadata] is empty for this container. Returns the frames given to LAME. */
    override fun finish(metadata: List<MetadataChunk>): Long {
        writeFully(stream.flush())
        val info = stream.lametagFrame()
        val end = output.position()
        if (info.isNotEmpty() && end - audioStart >= info.size) {
            output.position(audioStart)
            writeFully(info)
            output.position(end)
        }
        output.truncate(end)
        return frames
    }

    override fun close() = stream.close()

    private fun writeFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) output.write(buffer)
    }
}
