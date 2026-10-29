package io.sonora.core.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import io.sonora.core.domain.audio.PcmSampleFormat
import java.nio.ByteBuffer

/*
 * The seam between [MediaCodecPcmReader]'s decode loop and the platform, so the loop runs on the JVM in tests.
 * Index, flag and encoding values are MediaCodec's and AudioFormat's.
 */

/** What the reader knows about the selected track from its container. Null fields are keys the track lacks. */
internal data class TrackInfo(
    val mime: String,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val durationUs: Long?,
    val maxInputSize: Int?,
    /** AudioFormat encoding of an `audio/raw` track. */
    val pcmEncoding: Int?,
    /** The source's sample storage when the container states it; null for lossy codecs. */
    val sourceSampleFormat: PcmSampleFormat?,
    /** Frames to drop from the start of the decoded audio (`encoder-delay`: LAME header, iTunSMPB, MP4 edit list). */
    val encoderDelayFrames: Int = 0,
    /** Frames to drop from the end of the decoded audio (`encoder-padding`). */
    val encoderPaddingFrames: Int = 0,
)

/** The decoder output format fields the reader honours. Null fields are keys the format lacks. */
internal data class AudioOutputFormat(val pcmEncoding: Int?, val sampleRateHz: Int?, val channelCount: Int?)

/** The selected track of a demuxer: [MediaExtractor] after `selectTrack`. */
internal interface SampleSource {
    /** Copies the current sample into [buffer] from [offset]; its size in bytes, or a negative value at the end. */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    /** Presentation time of the current sample. */
    val sampleTime: Long

    fun advance()

    /** Releases the demuxer and its file; never throws. */
    fun release()
}

/** Where a decoded output buffer's data sits; filled by [DecoderCodec.dequeueOutputBuffer]. */
internal class OutputBufferInfo {
    var offset = 0
    var size = 0
    var flags = 0
}

/** A started synchronous decoder: the [MediaCodec] calls the decode loop makes. */
internal interface DecoderCodec {
    fun dequeueInputBuffer(timeoutUs: Long): Int

    fun getInputBuffer(index: Int): ByteBuffer?

    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)

    /** An output buffer index, or a negative `MediaCodec.INFO_*` value. */
    fun dequeueOutputBuffer(info: OutputBufferInfo, timeoutUs: Long): Int

    fun getOutputBuffer(index: Int): ByteBuffer?

    fun releaseOutputBuffer(index: Int)

    val outputFormat: AudioOutputFormat

    /** Stops and releases the codec; never throws. */
    fun release()
}

/** A created but not yet configured decoder. */
internal interface ConfigurableCodec {
    /** Configures for decoding, asking for float PCM output when [floatOutput]; throws when the codec refuses. */
    fun configure(floatOutput: Boolean)

    fun reset()

    fun start()
}

/**
 * Configures [codec] for float output and starts it. A codec that refuses float output (IllegalArgumentException or
 * a CodecException) is reset and configured without the request: the reader then honours whatever it outputs.
 */
internal fun configureAndStart(codec: ConfigurableCodec) {
    try {
        codec.configure(floatOutput = true)
    } catch (e: IllegalArgumentException) {
        codec.reset()
        codec.configure(floatOutput = false)
    } catch (e: MediaCodec.CodecException) {
        codec.reset()
        codec.configure(floatOutput = false)
    }
    codec.start()
}

/** [SampleSource] over an extractor that owns [descriptor], released with it. */
internal class ExtractorSampleSource(
    private val extractor: MediaExtractor,
    private val descriptor: ParcelFileDescriptor,
) : SampleSource {
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)

    override val sampleTime: Long get() = extractor.sampleTime

    override fun advance() {
        extractor.advance()
    }

    override fun release() {
        runCatching { extractor.release() }
        runCatching { descriptor.close() }
    }
}

/** [DecoderCodec] and [ConfigurableCodec] over a platform decoder for a track of [trackFormat]. */
internal class PlatformDecoderCodec(
    private val codec: MediaCodec,
    private val trackFormat: MediaFormat,
) : DecoderCodec, ConfigurableCodec {

    private val info = MediaCodec.BufferInfo()

    /**
     * Configures the codec with a copy of the track format WITHOUT `encoder-delay`/`encoder-padding`: the reader trims
     * those itself, exactly (see [MediaCodecPcmReader]); the codec would trim them as well, and wrongly for float output.
     */
    override fun configure(floatOutput: Boolean) {
        val format = MediaFormat(trackFormat).apply {
            removeKey(MediaCodecPcmReader.KEY_ENCODER_DELAY)
            removeKey(MediaCodecPcmReader.KEY_ENCODER_PADDING)
            if (floatOutput) setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        codec.configure(format, null, null, 0)
    }

    override fun reset() = codec.reset()

    override fun start() = codec.start()

    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)

    override fun dequeueOutputBuffer(info: OutputBufferInfo, timeoutUs: Long): Int {
        val index = codec.dequeueOutputBuffer(this.info, timeoutUs)
        info.offset = this.info.offset
        info.size = this.info.size
        info.flags = this.info.flags
        return index
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun releaseOutputBuffer(index: Int) = codec.releaseOutputBuffer(index, false)

    override val outputFormat: AudioOutputFormat get() = codec.outputFormat.toAudioOutputFormat()

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

internal fun MediaFormat.toAudioOutputFormat(): AudioOutputFormat = AudioOutputFormat(
    pcmEncoding = integer(MediaFormat.KEY_PCM_ENCODING),
    sampleRateHz = integer(MediaFormat.KEY_SAMPLE_RATE),
    channelCount = integer(MediaFormat.KEY_CHANNEL_COUNT),
)

internal fun MediaFormat.integer(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

internal fun MediaFormat.long(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
