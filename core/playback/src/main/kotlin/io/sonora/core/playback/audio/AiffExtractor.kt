package io.sonora.core.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import io.sonora.core.domain.audio.AiffException
import io.sonora.core.domain.audio.AiffHeader
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.audio.PcmLayout.SampleKind
import java.io.EOFException
import java.nio.ByteOrder

/**
 * Media3 extractor for AIFF and AIFF-C (Media3 ships none). The header comes from the shared [AiffParser]; the sound
 * data is passed through as raw PCM in its stored encoding, big-endian included (Media3's audio sink converts it, to
 * float for high-resolution input when float output is on), so no precision is lost here. The one exception is
 * 8-bit: AIFF stores it signed while Media3's 8-bit PCM is unsigned, so those bytes are flipped on the fly.
 *
 * Samples hold about 100 ms of whole frames, at most [MAX_SAMPLE_BYTES]; seeking is exact to the frame.
 */
@UnstableApi
internal class AiffExtractor : Extractor {

    private lateinit var extractorOutput: ExtractorOutput
    private lateinit var trackOutput: TrackOutput
    private var header: AiffHeader? = null

    /** Whether the input still has to be moved to the first frame after the header was parsed. */
    private var seekToData = false
    private var targetBytes = 0
    private var buffer = ByteArray(0)
    private val wrapper = ParsableByteArray()
    private var pendingBytes = 0
    private var pendingStart = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val prefix = ByteArray(12)
        return try {
            input.peekFully(prefix, 0, prefix.size, /* allowEndOfInput = */ true) && AiffParser.isAiff(prefix)
        } catch (_: EOFException) {
            false // Shorter than a FORM header.
        }
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val header = header ?: run {
            // A header read cut short (I/O error, cancelled load) starts over from the beginning.
            if (input.position != 0L) {
                seekPosition.position = 0
                return Extractor.RESULT_SEEK
            }
            readHeader(input)
        }
        if (seekToData || input.position < header.dataStart) {
            seekToData = false
            val gap = header.dataStart - input.position
            if (gap !in 0..MAX_SKIP_BYTES) {
                seekPosition.position = header.dataStart
                return Extractor.RESULT_SEEK
            }
            input.skipFully(gap.toInt())
        }
        if (pendingBytes == 0) pendingStart = input.position
        val left = header.dataStart + header.dataLength - input.position
        if (left <= 0) return endOfData()
        val read = input.read(buffer, pendingBytes, minOf(left, (targetBytes - pendingBytes).toLong()).toInt())
        if (read == C.RESULT_END_OF_INPUT) return endOfData()
        pendingBytes += read
        if (pendingBytes == targetBytes || read.toLong() == left) commitSample()
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingBytes = 0
    }

    override fun release() = Unit

    private fun readHeader(input: ExtractorInput): AiffHeader {
        val header = try {
            AiffParser.parse(ExtractorInputSource(input))
        } catch (e: AiffException) {
            throw when (e) {
                is AiffException.UnsupportedCompression, is AiffException.UnsupportedSampleSize,
                is AiffException.UnsupportedSampleRate, is AiffException.UnsupportedChannelCount ->
                    ParserException.createForUnsupportedContainerFeature(e.message)
                else -> ParserException.createForMalformedContainer(e.message, e)
            }
        }
        val frameSize = header.bytesPerFrame
        val framesPerSample = minOf(header.sampleRateHz / SAMPLES_PER_SECOND, MAX_SAMPLE_BYTES / frameSize)
        targetBytes = maxOf(1, framesPerSample) * frameSize
        buffer = ByteArray(targetBytes)
        val bitrate = (header.sampleRateHz.toLong() * frameSize * 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        trackOutput.format(
            Format.Builder()
                .setContainerMimeType(CONTAINER_MIME_TYPE)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setAverageBitrate(bitrate)
                .setPeakBitrate(bitrate)
                .setMaxInputSize(targetBytes)
                .setChannelCount(header.channelCount)
                .setSampleRate(header.sampleRateHz)
                .setPcmEncoding(pcmEncoding(header.layout))
                .build(),
        )
        extractorOutput.seekMap(AiffSeekMap(header))
        this.header = header
        seekToData = true
        return header
    }

    /** Commits the whole frames read so far (a partial frame only remains when the file is cut short). */
    private fun commitSample() {
        val header = checkNotNull(header)
        val bytes = pendingBytes - pendingBytes % header.bytesPerFrame
        pendingBytes = 0
        if (bytes == 0) return
        if (header.layout.bitsPerSample == 8 && header.layout.kind == SampleKind.SIGNED_INT) {
            for (i in 0 until bytes) buffer[i] = (buffer[i].toInt() xor 0x80).toByte()
        }
        wrapper.reset(buffer, bytes)
        trackOutput.sampleData(wrapper, bytes)
        val frame = (pendingStart - header.dataStart) / header.bytesPerFrame
        trackOutput.sampleMetadata(frameTimeUs(frame, header), C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null)
    }

    private fun endOfData(): Int {
        commitSample()
        return Extractor.RESULT_END_OF_INPUT
    }

    private class AiffSeekMap(private val header: AiffHeader) : SeekMap {
        override fun isSeekable() = true

        override fun getDurationUs() = header.durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val lastFrame = maxOf(0L, header.frameCount - 1)
            val frame = (maxOf(0L, timeUs) * header.sampleRateHz / C.MICROS_PER_SECOND).coerceAtMost(lastFrame)
            return SeekMap.SeekPoints(SeekPoint(frameTimeUs(frame, header), header.dataStart + frame * header.bytesPerFrame))
        }
    }

    /** Reads the header straight from the player's input; skips larger than an Int (huge chunks) go in steps. */
    private class ExtractorInputSource(private val input: ExtractorInput) : AiffSource {
        override fun readFully(target: ByteArray, offset: Int, length: Int) = input.readFully(target, offset, length)

        override fun skipFully(byteCount: Long) {
            var left = byteCount
            while (left > 0) {
                val step = minOf(left, Int.MAX_VALUE.toLong()).toInt()
                input.skipFully(step)
                left -= step
            }
        }
    }

    companion object {
        /** Not a Media3 constant; informational only. */
        const val CONTAINER_MIME_TYPE = "audio/x-aiff"

        /** Samples per second of audio: each sample holds 100 ms of frames, like Media3's WAV extractor. */
        private const val SAMPLES_PER_SECOND = 10

        /** Upper bound on one sample (and the read buffer), whatever the frame size; rounded down to whole frames. */
        const val MAX_SAMPLE_BYTES = 1024 * 1024

        /** Gaps up to this size between the header and the first frame are skipped rather than seeked over. */
        private const val MAX_SKIP_BYTES = 256 * 1024L

        /** Exact integer time of [frame] (a double scale would round some frame times down by a microsecond). */
        fun frameTimeUs(frame: Long, header: AiffHeader) = frame * C.MICROS_PER_SECOND / header.sampleRateHz

        fun pcmEncoding(layout: PcmLayout): Int {
            val bigEndian = layout.byteOrder == ByteOrder.BIG_ENDIAN
            return when (layout.kind) {
                SampleKind.SIGNED_INT, SampleKind.UNSIGNED_INT -> when (layout.bitsPerSample) {
                    8 -> C.ENCODING_PCM_8BIT
                    16 -> if (bigEndian) C.ENCODING_PCM_16BIT_BIG_ENDIAN else C.ENCODING_PCM_16BIT
                    24 -> if (bigEndian) C.ENCODING_PCM_24BIT_BIG_ENDIAN else C.ENCODING_PCM_24BIT
                    32 -> if (bigEndian) C.ENCODING_PCM_32BIT_BIG_ENDIAN else C.ENCODING_PCM_32BIT
                    else -> unsupported(layout)
                }
                SampleKind.FLOAT -> when (layout.bitsPerSample) {
                    32 -> if (bigEndian) C.ENCODING_PCM_FLOAT_BIG_ENDIAN else C.ENCODING_PCM_FLOAT
                    64 -> if (bigEndian) C.ENCODING_PCM_DOUBLE_BIG_ENDIAN else C.ENCODING_PCM_DOUBLE
                    else -> unsupported(layout)
                }
            }
        }

        private fun unsupported(layout: PcmLayout): Nothing =
            throw ParserException.createForUnsupportedContainerFeature("No Media3 PCM encoding for $layout")
    }
}
