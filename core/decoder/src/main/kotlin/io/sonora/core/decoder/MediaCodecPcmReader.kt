package io.sonora.core.decoder

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.audio.PcmLayout.SampleKind
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.toSampleFormat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes everything except AIFF with the platform: [MediaExtractor] demuxes the first audio track and a synchronous
 * [MediaCodec] decodes it. Float output is requested, but whatever encoding the codec reports is honoured, including
 * changes mid-stream. Tracks that already are PCM (`audio/raw`: WAV, and FLAC on platforms whose extractor decodes
 * it) are read straight from the extractor in the encoding it declares: no codec instance is needed for them, which
 * avoids depending on a raw "decoder" and copying every sample through it.
 *
 * Gapless trimming: the encoder delay and padding the extractor states (`encoder-delay`/`encoder-padding`: the LAME or
 * Xing header and iTunSMPB of an MP3, the edit list or iTunSMPB of an MP4) are trimmed here, in frames, from the start
 * and the end of the decoded audio, and NOT passed to the codec. Media3's player trims the same frames the same way
 * (its extractors read the same headers; its audio sink drops them), so the audio this reader returns has the
 * player's time base: the DJ analysis must, or its grid would sit off what is heard (D1.26). Left to the codec,
 * Codec2's `SkipCutBuffer` would trim them counting 16-bit frames even when it hands out float, so with the float
 * output asked for here it cut only half (AOSP `CCodecBuffers.cpp`, `SkipCutBuffer.cpp`). The MP3 decoder's own 529
 * frames of delay are dropped inside the platform codec, for this reader and the player alike.
 *
 * The reported format comes from the codec's first output format, not the container, so implicit HE-AAC (SBR doubles
 * the rate) and decoders that upmix report what they deliver.
 *
 * The decode loop talks to the platform through [SampleSource] and [DecoderCodec] (see `MediaCodecPorts.kt`), so it is
 * tested on the JVM. [cancel] makes a read in progress stop within one codec round.
 */
internal class MediaCodecPcmReader private constructor(
    private val sourceUri: String,
    private val track: TrackInfo,
    private val source: SampleSource,
    private val codec: DecoderCodec?,
) : BlockingPcmReader {

    private lateinit var pending: PendingPcm
    private var layout: PcmLayout = layoutOf(AudioFormat.ENCODING_PCM_16BIT)
    private var sampleRateHz = track.sampleRateHz ?: 0
    private var channelCount = track.channelCount ?: 0

    private var inputDone = false
    private var outputDone = false
    private val info = OutputBufferInfo()
    private var scratch = ByteArray(0)
    private val rawBuffer: ByteBuffer? = if (codec == null) {
        ByteBuffer.allocateDirect(maxOf(track.maxInputSize ?: 0, MIN_RAW_BUFFER_BYTES))
    } else {
        null
    }

    /** Set by [cancel] from another thread; the decode loops check it every round. */
    @Volatile private var cancelled = false

    private var primedFormat: PcmFormat? = null

    override val format: PcmFormat get() = checkNotNull(primedFormat) { "Not primed" }

    /** Reads until the output format is certain, then fixes [format]. */
    private fun prime() {
        applyOutputFormat(codec?.outputFormat ?: AudioOutputFormat(track.pcmEncoding, track.sampleRateHz, track.channelCount))
        if (channelCount <= 0 || sampleRateHz <= 0) {
            throw PcmDecodeException.UnsupportedFormat("${track.mime} with $channelCount channels at $sampleRateHz Hz")
        }
        pending = newPending()
        // The first block settles the codec's real output format (it is announced before the first output buffer).
        fill()
        primedFormat = PcmFormat(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            sourceSampleFormat = track.sourceSampleFormat,
            codec = track.mime,
            durationUs = track.durationUs?.takeIf { it > 0 },
            totalFrames = null,
        )
    }

    override fun read(buffer: FloatArray): Int = guarded {
        while (pending.isEmpty) {
            if (!fill()) {
                pending.endOfStream()
                if (pending.isEmpty) return@guarded -1
            }
        }
        pending.drainTo(buffer)
    }

    /** Makes a read in progress throw [StreamClosedException] within one codec round (about 10 ms). */
    override fun cancel() {
        cancelled = true
    }

    /** Adds at least one frame to [pending]; false once the stream has ended. */
    private fun fill(): Boolean = if (codec == null) fillRaw() else fillFromCodec(codec)

    private fun fillRaw(): Boolean {
        val buffer = checkNotNull(rawBuffer)
        while (!outputDone) {
            if (cancelled) throw StreamClosedException()
            buffer.clear()
            val size = source.readSampleData(buffer, 0)
            if (size < 0) {
                outputDone = true
                break
            }
            source.advance()
            if (size == 0) continue
            append(buffer, 0, size)
            if (!pending.isEmpty) return true
        }
        return false
    }

    private fun fillFromCodec(codec: DecoderCodec): Boolean {
        var idleRounds = 0
        while (!outputDone) {
            if (cancelled) throw StreamClosedException()
            var progressed = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val input = checkNotNull(codec.getInputBuffer(index))
                    val size = source.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, source.sampleTime, 0)
                        source.advance()
                    }
                    progressed = true
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index >= 0 -> {
                    progressed = true
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        append(checkNotNull(codec.getOutputBuffer(index)), info.offset, info.size)
                    }
                    codec.releaseOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (!pending.isEmpty) return true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    progressed = true
                    applyOutputFormat(codec.outputFormat)
                }
            }
            if (progressed) {
                idleRounds = 0
            } else if (++idleRounds > MAX_IDLE_ROUNDS) {
                throw PcmDecodeException.CorruptData("${track.mime} decoder stopped producing output")
            }
        }
        return false
    }

    private fun append(buffer: ByteBuffer, offset: Int, size: Int) {
        if (scratch.size < size) scratch = ByteArray(size)
        buffer.position(offset)
        buffer.get(scratch, 0, size)
        pending.append(scratch, 0, size, layout)
    }

    /** Takes the encoding, and before the first frame also the rate and channel count, of [output]. */
    private fun applyOutputFormat(output: AudioOutputFormat) {
        layout = layoutOf(output.pcmEncoding ?: AudioFormat.ENCODING_PCM_16BIT)
        val rate = output.sampleRateHz ?: sampleRateHz
        val channels = output.channelCount ?: channelCount
        if (primedFormat != null) {
            if (rate != sampleRateHz || channels != channelCount) {
                throw PcmDecodeException.CorruptData(
                    "${track.mime} changed from $channelCount channels at $sampleRateHz Hz to $channels at $rate Hz " +
                        "mid-stream",
                )
            }
        } else {
            val changed = rate != sampleRateHz || channels != channelCount
            if (changed && ::pending.isInitialized && !pending.holdsNothing && channels != channelCount) {
                throw PcmDecodeException.CorruptData("${track.mime} changed its channel count")
            }
            sampleRateHz = rate
            channelCount = channels
            // Before the first frame is out, a new pending store takes the new layout and what is left of the start trim.
            if (changed && ::pending.isInitialized && pending.holdsNothing) pending = newPending(pending.skippedFrames)
        }
    }

    /**
     * An empty [PendingPcm] for the current channel count, trimming the track's encoder delay and padding. They are
     * counted at the track's rate; when the codec outputs another (implicit HE-AAC doubles it), they are scaled to it,
     * as Codec2 scales its own trim. [alreadySkipped] frames of the start trim were dropped by an earlier store.
     */
    private fun newPending(alreadySkipped: Int = 0): PendingPcm {
        val trackRate = track.sampleRateHz
        fun scaled(frames: Int): Int = if (trackRate == null || trackRate <= 0 || trackRate == sampleRateHz) {
            frames
        } else {
            (frames.toLong() * sampleRateHz / trackRate).toInt()
        }
        return PendingPcm(
            channelCount,
            skipFrames = maxOf(scaled(track.encoderDelayFrames) - alreadySkipped, 0),
            cutFrames = scaled(track.encoderPaddingFrames),
        )
    }

    /** Runs a decode step, turning platform failures into [PcmDecodeException]s. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: PcmDecodeException) {
        throw e
    } catch (e: StreamClosedException) {
        throw e
    } catch (e: IOException) {
        throw PcmDecodeException.SourceUnavailable(sourceUri, e)
    } catch (e: IllegalStateException) {
        // MediaCodec.CodecException and failures of a codec in the wrong state.
        throw PcmDecodeException.CorruptData("${track.mime} decoder failed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        // MediaExtractor.readSampleData with a sample that does not fit, or a malformed sample.
        throw PcmDecodeException.CorruptData("${track.mime} sample could not be read: ${e.message}", e)
    }

    override fun close() {
        codec?.release()
        source.release()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L

        /** About five seconds of neither input taken nor output given before a codec counts as stuck. */
        private const val MAX_IDLE_ROUNDS = 250
        private const val MIN_RAW_BUFFER_BYTES = 256 * 1024

        // AudioFormat constants added in API 31 (minSdk is 29); decoders may report them earlier.
        private const val ENCODING_PCM_24BIT_PACKED = 21
        private const val ENCODING_PCM_32BIT = 22

        /** MediaFormat key the platform extractors use for the source bit depth (FLAC, WAV, ALAC). */
        private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

        /** `MediaFormat.KEY_ENCODER_DELAY` and `KEY_ENCODER_PADDING` (public since API 30; the platform sets them earlier). */
        internal const val KEY_ENCODER_DELAY = "encoder-delay"
        internal const val KEY_ENCODER_PADDING = "encoder-padding"

        /**
         * Opens [sourceUri] through [resolver] and decodes its first audio track up to the first block.
         *
         * @throws PcmDecodeException when it cannot be opened or decoded.
         */
        fun open(resolver: ContentResolver, sourceUri: String): MediaCodecPcmReader {
            val descriptor = try {
                resolver.openFileDescriptor(sourceUri.toUri(), "r")
            } catch (e: IOException) {
                throw PcmDecodeException.SourceUnavailable(sourceUri, e)
            } catch (e: SecurityException) {
                throw PcmDecodeException.SourceUnavailable(sourceUri, e)
            } catch (e: IllegalArgumentException) {
                throw PcmDecodeException.SourceUnavailable(sourceUri, e)
            } ?: throw PcmDecodeException.SourceUnavailable(sourceUri)

            val extractor = MediaExtractor()
            val source = ExtractorSampleSource(extractor, descriptor)
            val track: TrackInfo
            val codec: DecoderCodec?
            try {
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                } catch (e: IOException) {
                    throw PcmDecodeException.UnsupportedFormat("no platform extractor reads this file", e)
                }
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw PcmDecodeException.UnsupportedFormat("no audio track")
                extractor.selectTrack(index)
                val trackFormat = extractor.getTrackFormat(index)
                val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
                track = trackFormat.toTrackInfo(mime)
                // startCodec releases its own codec when it fails.
                codec = if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) startCodec(mime, trackFormat) else null
            } catch (e: Throwable) {
                source.release()
                throw when (e) {
                    is PcmDecodeException -> e
                    is IOException -> PcmDecodeException.SourceUnavailable(sourceUri, e)
                    is IllegalStateException, is IllegalArgumentException ->
                        PcmDecodeException.CorruptData(e.message ?: "platform decoder failed", e)
                    else -> e
                }
            }
            return create(sourceUri, track, source, codec)
        }

        /**
         * A reader over [source] (and [codec], unless the track is `audio/raw`), primed with its first block. On
         * failure both are released and the failure is a [PcmDecodeException].
         */
        internal fun create(
            sourceUri: String,
            track: TrackInfo,
            source: SampleSource,
            codec: DecoderCodec?,
        ): MediaCodecPcmReader = MediaCodecPcmReader(sourceUri, track, source, codec).apply {
            try {
                guarded { prime() }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        /** A started decoder for [mime], asked for float output; without that request if the codec refuses it. */
        private fun startCodec(mime: String, trackFormat: MediaFormat): DecoderCodec {
            val platform = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IOException) {
                throw PcmDecodeException.UnsupportedFormat("no decoder for $mime", e)
            } catch (e: IllegalArgumentException) {
                throw PcmDecodeException.UnsupportedFormat("no decoder for $mime", e)
            }
            val codec = PlatformDecoderCodec(platform, trackFormat)
            try {
                configureAndStart(codec)
                return codec
            } catch (e: Throwable) {
                runCatching { platform.release() }
                throw when (e) {
                    is PcmDecodeException -> e
                    is IllegalArgumentException, is IllegalStateException ->
                        PcmDecodeException.UnsupportedFormat("$mime decoder rejected the track: ${e.message}", e)
                    else -> e
                }
            }
        }

        private fun MediaFormat.toTrackInfo(mime: String) = TrackInfo(
            mime = mime,
            sampleRateHz = integer(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = integer(MediaFormat.KEY_CHANNEL_COUNT),
            durationUs = long(MediaFormat.KEY_DURATION),
            maxInputSize = integer(MediaFormat.KEY_MAX_INPUT_SIZE),
            pcmEncoding = integer(MediaFormat.KEY_PCM_ENCODING),
            sourceSampleFormat = sourceSampleFormat(this),
            encoderDelayFrames = integer(KEY_ENCODER_DELAY)?.coerceAtLeast(0) ?: 0,
            encoderPaddingFrames = integer(KEY_ENCODER_PADDING)?.coerceAtLeast(0) ?: 0,
        )

        private fun layoutOf(encoding: Int): PcmLayout {
            val order = ByteOrder.nativeOrder()
            return when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> PcmLayout(SampleKind.UNSIGNED_INT, 8, order)
                AudioFormat.ENCODING_PCM_16BIT -> PcmLayout(SampleKind.SIGNED_INT, 16, order)
                ENCODING_PCM_24BIT_PACKED -> PcmLayout(SampleKind.SIGNED_INT, 24, order)
                ENCODING_PCM_32BIT -> PcmLayout(SampleKind.SIGNED_INT, 32, order)
                AudioFormat.ENCODING_PCM_FLOAT -> PcmLayout(SampleKind.FLOAT, 32, order)
                else -> throw PcmDecodeException.UnsupportedFormat("PCM encoding $encoding")
            }
        }

        /** The source's sample storage when the container states it; `null` for lossy codecs. */
        private fun sourceSampleFormat(trackFormat: MediaFormat): PcmSampleFormat? {
            when (trackFormat.integer(KEY_BITS_PER_SAMPLE)) {
                8 -> return PcmSampleFormat.INT8
                16 -> return PcmSampleFormat.INT16
                24 -> return PcmSampleFormat.INT24
                32 -> return PcmSampleFormat.INT32
            }
            if (trackFormat.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_RAW) return null
            val encoding = trackFormat.integer(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
            return runCatching { layoutOf(encoding).toSampleFormat() }.getOrNull()
        }
    }
}
