package io.sonora.core.transcoder

import io.sonora.core.transcoder.encoder.OpusFrameCodec

/**
 * A stand-in for the platform Opus encoder: every frame becomes one 20 ms packet (TOC 0xFC) at once, and the end of
 * the stream adds one more, like a real encoder flushing its look-ahead.
 */
class FakeOpusCodec(override val preSkip: Int? = 312) : OpusFrameCodec {
    var channels = 0
    var bitrateBps = 0
    val frames = ArrayList<ByteArray>()
    var endOfStreamAfterFrames = -1
        private set
    var closed = false
        private set

    override fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit) {
        if (length > 0) {
            frames += pcm.copyOf(length)
            sink(packet(frames.size))
        }
        if (endOfStream) {
            endOfStreamAfterFrames = frames.size
            sink(packet(frames.size + 1))
        }
    }

    override fun close() {
        closed = true
    }

    private fun packet(number: Int) = ByteArray(60) { if (it == 0) 0xFC.toByte() else (number + it).toByte() }
}
