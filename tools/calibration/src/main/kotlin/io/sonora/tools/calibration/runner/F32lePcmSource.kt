package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.pipeline.PcmSource
import java.io.InputStream

/**
 * A [PcmSource] over a stream of interleaved 32-bit little-endian floats, the `-f f32le` output of ffmpeg. Reads as
 * many whole frames as the buffer holds and the stream has ready (at least one, blocking for it), keeps a partial frame
 * for the next call, and drops a partial frame at the very end. It cannot restart (a pipe cannot rewind), which is
 * what [io.sonora.core.djengine.pipeline.FallbackPolicy.ONLY_WITHOUT_MODEL] expects of a source without a second
 * decode. The caller closes [input].
 */
class F32lePcmSource(
    private val input: InputStream,
    override val sampleRateHz: Int,
    override val channelCount: Int,
    override val totalFrames: Long? = null,
) : PcmSource {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
    }

    private val frameBytes = channelCount * BYTES_PER_SAMPLE
    private var bytes = ByteArray(0)

    /** Bytes of [bytes] that hold data not delivered yet (always fewer than one frame between calls). */
    private var pending = 0
    private var ended = false

    /** Whole frames delivered so far. */
    var framesRead: Long = 0
        private set

    override fun read(buffer: FloatArray): Int {
        val maxFrames = buffer.size / channelCount
        require(maxFrames >= 1) { "The buffer (${buffer.size}) holds no whole frame of $channelCount channels" }
        if (ended) return END
        val wanted = maxFrames * frameBytes
        if (bytes.size < wanted) bytes = bytes.copyOf(wanted)

        // Block for at least one whole frame, then take whatever else is already there without blocking again.
        while (pending < frameBytes) {
            val n = input.read(bytes, pending, wanted - pending)
            if (n < 0) {
                ended = true
                return END
            }
            pending += n
        }
        while (pending < wanted && input.available() > 0) {
            val n = input.read(bytes, pending, wanted - pending)
            if (n <= 0) break
            pending += n
        }

        val frames = pending / frameBytes
        val samples = frames * channelCount
        var at = 0
        for (i in 0 until samples) {
            val bits = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
            buffer[i] = Float.fromBits(bits)
            at += BYTES_PER_SAMPLE
        }
        val used = frames * frameBytes
        bytes.copyInto(bytes, 0, used, pending)
        pending -= used
        framesRead += frames
        return frames
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 4
        const val END = -1
    }
}
