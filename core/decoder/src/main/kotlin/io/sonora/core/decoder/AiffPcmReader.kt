package io.sonora.core.decoder

import io.sonora.core.domain.audio.AiffException
import io.sonora.core.domain.audio.AiffHeader
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSamples
import io.sonora.core.domain.audio.toSampleFormat
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Streams the sound data of an AIFF / AIFF-C file (which the platform's MediaExtractor cannot read). The header comes
 * from the shared [AiffParser], so every sample format it accepts decodes here: integer PCM 1-32 bits big-endian
 * (`NONE`, `twos`, `in24`, `in32`) or little-endian (`sowt`), 8-bit `raw `, and big-endian `fl32` / `fl64`.
 * Pure JVM: [openStream] supplies the bytes (a SAF input stream on the device, a byte array in tests).
 */
internal class AiffPcmReader private constructor(
    private val sourceUri: String,
    private val input: InputStream,
    private val header: AiffHeader,
) : BlockingPcmReader {

    override val format = PcmFormat(
        sampleRateHz = header.sampleRateHz,
        channelCount = header.channelCount,
        sourceSampleFormat = header.layout.toSampleFormat(),
        codec = if (header.isAifc) "AIFF-C ${header.compressionType.trim()}" else "AIFF",
        durationUs = header.durationUs,
        totalFrames = header.frameCount,
    )

    private var remainingFrames = header.frameCount
    private var bytes = ByteArray(0)

    override fun read(buffer: FloatArray): Int {
        val channels = header.channelCount
        require(buffer.size >= channels) { "A buffer of ${buffer.size} samples cannot hold one of $channels-channel frames" }
        if (remainingFrames == 0L) return -1
        val frames = minOf(buffer.size / channels.toLong(), remainingFrames).toInt()
        val byteCount = frames * header.bytesPerFrame
        if (bytes.size < byteCount) bytes = ByteArray(byteCount)
        readSoundData(byteCount)
        PcmSamples.toFloat(bytes, 0, header.layout, buffer, 0, frames * channels)
        remainingFrames -= frames
        return frames
    }

    private fun readSoundData(byteCount: Int) {
        var done = 0
        try {
            while (done < byteCount) {
                val read = input.read(bytes, done, byteCount - done)
                if (read < 0) {
                    val missing = remainingFrames * header.bytesPerFrame - done
                    throw PcmDecodeException.CorruptData("AIFF sound data ends $missing bytes early")
                }
                done += read
            }
        } catch (e: PcmDecodeException) {
            throw e
        } catch (e: IOException) {
            throw PcmDecodeException.SourceUnavailable(sourceUri, e)
        }
    }

    override fun close() {
        runCatching { input.close() }
    }

    companion object {

        /**
         * Parses the header and positions a stream at the first frame. When the file stores its sound data before
         * COMM, the parser has to read past it, so a second stream is opened and skipped to the data.
         *
         * @throws PcmDecodeException when the header is unusable or the source cannot be read.
         */
        fun open(sourceUri: String, openStream: () -> InputStream): AiffPcmReader {
            val first = openSource(sourceUri, openStream)
            var stream = first
            try {
                val source = CountingSource(AiffSource.of(first))
                val header = AiffParser.parse(source)
                if (source.position > header.dataStart) {
                    first.close()
                    stream = openSource(sourceUri, openStream)
                    AiffSource.of(stream).skipFully(header.dataStart)
                } else {
                    source.skipFully(header.dataStart - source.position)
                }
                return AiffPcmReader(sourceUri, stream, header)
            } catch (e: Throwable) {
                runCatching { stream.close() }
                throw map(sourceUri, e)
            }
        }

        private fun openSource(sourceUri: String, openStream: () -> InputStream): InputStream = try {
            openStream()
        } catch (e: IOException) {
            throw PcmDecodeException.SourceUnavailable(sourceUri, e)
        } catch (e: SecurityException) {
            throw PcmDecodeException.SourceUnavailable(sourceUri, e)
        }

        private fun map(sourceUri: String, error: Throwable): Throwable = when (error) {
            is PcmDecodeException -> error
            is AiffException.NotAiff, is AiffException.UnsupportedCompression, is AiffException.UnsupportedSampleSize ->
                PcmDecodeException.UnsupportedFormat(error.message.orEmpty(), error)
            is AiffException, is EOFException -> PcmDecodeException.CorruptData(error.message ?: "AIFF ends early", error)
            is IOException, is SecurityException -> PcmDecodeException.SourceUnavailable(sourceUri, error)
            else -> error
        }
    }

    /** Tracks how far the parser has read, to know whether the stream still sits before the sound data. */
    private class CountingSource(private val source: AiffSource) : AiffSource {
        var position = 0L
            private set

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            source.readFully(target, offset, length)
            position += length
        }

        override fun skipFully(byteCount: Long) {
            source.skipFully(byteCount)
            position += byteCount
        }
    }
}
