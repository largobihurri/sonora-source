package io.sonora.core.transcoder

import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmSamples
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.domain.audio.toSampleFormat
import java.io.ByteArrayInputStream

/** A [PcmDecoder] over in-memory sources keyed by URI. */
class FakePcmDecoder(private val sources: Map<String, () -> PcmStream>) : PcmDecoder {
    override suspend fun open(sourceUri: String): PcmStream =
        (sources[sourceUri] ?: throw PcmDecodeException.SourceUnavailable(sourceUri)).invoke()
}

/** Delivers [samples] (interleaved) in blocks of at most [maxFrames]; [beforeRead] runs before each read. */
class FakePcmStream(
    override val format: PcmFormat,
    private val samples: FloatArray,
    private val maxFrames: Int = 1_000,
    private val beforeRead: suspend (readIndex: Int) -> Unit = {},
) : PcmStream {
    var closed = false
        private set
    private var frame = 0
    private var reads = 0

    override suspend fun read(buffer: FloatArray): Int {
        check(!closed) { "closed" }
        beforeRead(reads++)
        val channels = format.channelCount
        val total = samples.size / channels
        if (frame >= total) return -1
        val n = minOf(buffer.size / channels, maxFrames, total - frame)
        samples.copyInto(buffer, 0, frame * channels, (frame + n) * channels)
        frame += n
        return n
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun format(rate: Int, channels: Int, sampleFormat: PcmSampleFormat?, frames: Long?) =
            PcmFormat(rate, channels, sampleFormat, codec = "fake", durationUs = null, totalFrames = frames)
    }
}

/** Decodes an AIFF held in memory, like the real decoder does for files we wrote. */
fun aiffStream(bytes: ByteArray): PcmStream {
    val header = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))
    val samples = FloatArray((header.frameCount * header.channelCount).toInt())
    PcmSamples.toFloat(bytes, header.dataStart.toInt(), header.layout, samples, 0, samples.size)
    val format = PcmFormat(
        header.sampleRateHz, header.channelCount, header.layout.toSampleFormat(), "AIFF", header.durationUs, header.frameCount,
    )
    return FakePcmStream(format, samples)
}
