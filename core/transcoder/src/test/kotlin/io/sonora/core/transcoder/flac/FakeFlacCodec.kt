package io.sonora.core.transcoder.flac

import io.sonora.core.transcoder.encoder.FlacFrameCodec

/**
 * A stand-in for the platform FLAC encoder: states a STREAMINFO for [bitsPerSample] (which may differ from what was
 * asked, as a 16-bit-only encoder would) once the first block arrives, and turns every block into one "frame" that
 * is the block itself behind a frame sync code.
 */
class FakeFlacCodec(
    private val sampleRateHz: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    override val floatInput: Boolean,
) : FlacFrameCodec {
    val blocks = ArrayList<ByteArray>()
    var closed = false
        private set
    override var streamInfo: ByteArray? = null
        private set

    override fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit) {
        if (streamInfo == null) streamInfo = FlacBlocks.streamInfo(sampleRateHz, channels, bitsPerSample)
        if (length > 0) {
            blocks += pcm.copyOf(length)
            sink(byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + pcm.copyOf(length))
        }
    }

    override fun close() {
        closed = true
    }
}
