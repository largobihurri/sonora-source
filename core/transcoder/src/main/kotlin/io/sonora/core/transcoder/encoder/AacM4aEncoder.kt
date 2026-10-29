package io.sonora.core.transcoder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.dsp.TpdfQuantizer
import io.sonora.core.transcoder.mp4.Mp4MetadataInjector
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Builds [AacM4aEncoder]s: AAC-LC at the profile's bitrate on the platform encoder, muxed by MediaMuxer into a
 * temporary file in [tempDir] (MediaMuxer writes only to files, not to a document's channel). A device without an
 * AAC encoder for the plan's rate and channels fails the file as unsupported.
 */
class AacEncoderFactory(private val tempDir: () -> File) : AudioEncoderFactory {
    override fun create(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long): AudioEncoder {
        val bitrate = requireNotNull(plan.profile.bitrateBps) { "Profile ${plan.profile.id} states no AAC bitrate" }
        val encoder = findEncoder() ?: throw UnsupportedProfileException(plan.profile, "this device has no AAC encoder ($MIME)")
        val audio = encoder.getCapabilitiesForType(MIME).audioCapabilities
        if (audio != null && (!audio.isSampleRateSupported(plan.targetSampleRateHz) || plan.targetChannels > audio.maxInputChannelCount)) {
            throw UnsupportedProfileException(
                plan.profile,
                "the AAC encoder does not take ${plan.targetSampleRateHz} Hz x ${plan.targetChannels}",
            )
        }
        val format = MediaFormat.createAudioFormat(MIME, plan.targetSampleRateHz, plan.targetChannels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, audio?.bitrateRange?.clamp(bitrate) ?: bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_FRAMES * plan.targetChannels * 2)
        val codec = try {
            MediaCodec.createByCodecName(encoder.name)
        } catch (e: IOException) {
            throw UnsupportedProfileException(plan.profile, "cannot create ${encoder.name}: ${e.message}", e)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            throw UnsupportedProfileException(plan.profile, "${encoder.name} rejected $format: ${e.message}", e)
        }
        var temp: File? = null
        try {
            temp = File.createTempFile("sonora-aac-", ".m4a", tempDir())
            return AacM4aEncoder(plan, output, codec, temp, ditherSeed)
        } catch (e: Throwable) {
            runCatching { codec.stop() }
            codec.release()
            temp?.delete()
            throw e
        }
    }

    private fun findEncoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
        .maxByOrNull { if (it.isSoftwareOnly) 1 else 0 }

    internal companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC

        /** Frames per input buffer: two AAC frames. */
        const val INPUT_FRAMES = 2_048
    }
}

/**
 * AAC-LC in an M4A file: float frames become 16-bit little-endian PCM (the AAC encoder's input), [codec] encodes
 * them, MediaMuxer writes `ftyp`/`moov`/`mdat` into [temp], and [finish] copies that file into [output] with the
 * tags added ([Mp4MetadataInjector], every chunk offset kept right). [temp] is deleted in [close].
 */
class AacM4aEncoder(
    private val plan: ConversionPlan,
    private val output: SeekableByteChannel,
    private val codec: MediaCodec,
    private val temp: File,
    ditherSeed: Long,
) : AudioEncoder {

    private val channels = plan.targetChannels
    private val quantizer = TpdfQuantizer(ditherSeed, dither = plan.dither)
    private val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var pcm = ByteArray(0)
    private var track = -1
    private var muxerStarted = false
    private var muxerReleased = false
    private var codecReleased = false
    private var outputEnded = false
    private var framesQueued = 0L

    override fun write(samples: FloatArray, frames: Int) {
        var at = 0
        while (at < frames) {
            val n = minOf(frames - at, AacEncoderFactory.INPUT_FRAMES)
            val bytes = n * channels * 2
            if (pcm.size < bytes) pcm = ByteArray(bytes)
            quantizer.quantizeToLittleEndian(samples, n * channels, pcm, from = at * channels)
            queue(bytes, endOfStream = false)
            at += n
        }
    }

    /** Ends the stream, closes the muxer and copies the file into the output with [metadata]'s `ilst`, if any. */
    override fun finish(metadata: List<MetadataChunk>): Long {
        queue(0, endOfStream = true)
        val deadline = System.nanoTime() + TIMEOUT_NS
        while (!outputEnded) {
            if (System.nanoTime() > deadline) throw IOException("AAC encoder did not finish")
            drain(POLL_US)
        }
        if (!muxerStarted) throw IOException("AAC encoder gave no output format")
        muxer.stop()
        releaseMuxer()
        val ilst = metadata.firstOrNull { it.id == ILST_CHUNK_ID }?.data
        FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { source ->
            val start = output.position()
            if (ilst != null) {
                Mp4MetadataInjector.inject(source, output, ilst)
            } else {
                var copied = 0L
                while (copied < source.size()) copied += source.transferTo(copied, source.size() - copied, output)
            }
            output.truncate(output.position().coerceAtLeast(start))
        }
        return framesQueued
    }

    override fun close() {
        if (!codecReleased) {
            codecReleased = true
            runCatching { codec.stop() }
            codec.release()
        }
        releaseMuxer()
        temp.delete()
    }

    private fun releaseMuxer() {
        if (muxerReleased) return
        muxerReleased = true
        runCatching { muxer.release() }
    }

    private fun queue(bytes: Int, endOfStream: Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NS
        var index: Int
        while (true) {
            index = codec.dequeueInputBuffer(0)
            if (index >= 0) break
            if (System.nanoTime() > deadline) throw IOException("AAC encoder stopped taking input")
            drain(POLL_US)
        }
        val buffer = codec.getInputBuffer(index) ?: throw IOException("AAC encoder gave no input buffer")
        buffer.clear()
        if (buffer.capacity() < bytes) throw IOException("AAC encoder input buffer (${buffer.capacity()} bytes) is smaller than $bytes")
        buffer.put(pcm, 0, bytes)
        val ptsUs = framesQueued * 1_000_000L / plan.targetSampleRateHz
        codec.queueInputBuffer(index, 0, bytes, ptsUs, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        framesQueued += bytes / (channels * 2)
        drain(0)
    }

    /** Muxes every ready AAC frame, waiting up to [timeoutUs] for the first one. */
    private fun drain(timeoutUs: Long) {
        var wait = timeoutUs
        while (!outputEnded) {
            val index = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "AAC encoder changed its output format twice" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !config) {
                        if (!muxerStarted) throw IOException("AAC encoder gave data before its format")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                }
            }
        }
    }

    companion object {
        /** Id of the [MetadataChunk] that carries the complete `ilst` box. */
        const val ILST_CHUNK_ID = "ilst"

        private const val POLL_US = 10_000L
        private const val TIMEOUT_NS = 10_000_000_000L
    }
}
