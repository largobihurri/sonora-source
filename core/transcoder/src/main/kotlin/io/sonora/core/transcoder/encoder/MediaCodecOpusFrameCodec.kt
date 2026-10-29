package io.sonora.core.transcoder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import io.sonora.core.transcoder.ogg.OpusHeaders
import io.sonora.core.transcoder.ogg.OpusStreamConfig
import java.io.IOException
import java.nio.ByteOrder

/**
 * The platform Opus encoder (`audio/opus`, `c2.android.opus.encoder` on API 29+) driven synchronously: one 20 ms
 * frame per input buffer, the output drained between frames. It reads the pre-skip from the codec's `csd-0`
 * (`OpusHead`) or `csd-1` (codec delay in ns) and returns raw Opus packets; the Ogg framing is Sonora's own
 * ([OggOpusWriter][io.sonora.core.transcoder.ogg.OggOpusWriter]).
 */
class MediaCodecOpusFrameCodec private constructor(private val codec: MediaCodec) : OpusFrameCodec {

    private val info = MediaCodec.BufferInfo()
    private var frameIndex = 0L
    private var outputEnded = false
    private var closed = false
    private var opusHead: ByteArray? = null
    private var codecDelayNs: Long? = null

    override val preSkip: Int?
        get() = if (opusHead != null || codecDelayNs != null) OpusStreamConfig.preSkip(opusHead, codecDelayNs) else null

    override fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit) {
        val index = awaitInputBuffer(sink)
        val buffer = codec.getInputBuffer(index) ?: throw IOException("Opus encoder gave no input buffer")
        if (buffer.capacity() < length) throw IOException("Opus encoder input buffer (${buffer.capacity()} bytes) is smaller than a frame ($length)")
        buffer.clear()
        buffer.put(pcm, 0, length)
        codec.queueInputBuffer(index, 0, length, frameIndex * FRAME_US, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        frameIndex++
        drain(sink, 0)
        if (endOfStream) {
            val deadline = System.nanoTime() + TIMEOUT_NS
            while (!outputEnded) {
                if (System.nanoTime() > deadline) throw IOException("Opus encoder did not finish")
                drain(sink, POLL_US)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
    }

    private fun awaitInputBuffer(sink: (ByteArray) -> Unit): Int {
        val deadline = System.nanoTime() + TIMEOUT_NS
        while (true) {
            val index = codec.dequeueInputBuffer(0)
            if (index >= 0) return index
            if (System.nanoTime() > deadline) throw IOException("Opus encoder stopped taking input")
            drain(sink, POLL_US)
        }
    }

    /** Hands every ready packet to [sink], waiting up to [timeoutUs] for the first one. */
    private fun drain(sink: (ByteArray) -> Unit, timeoutUs: Long) {
        var wait = timeoutUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readConfig(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (data.startsWith(OpusHeaders.HEAD_MAGIC)) opusHead = data
                        } else {
                            sink(data)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEnded = true
                        return
                    }
                }
            }
        }
    }

    private fun readConfig(format: MediaFormat) {
        format.getByteBuffer(CSD_HEAD)?.let { buffer ->
            val data = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            if (data.startsWith(OpusHeaders.HEAD_MAGIC)) opusHead = data
        }
        format.getByteBuffer(CSD_DELAY)?.let { buffer ->
            val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
            if (copy.remaining() >= Long.SIZE_BYTES) codecDelayNs = copy.getLong(copy.position())
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object Factory : OpusFrameCodecFactory {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val CSD_HEAD = "csd-0"
        private const val CSD_DELAY = "csd-1"
        private const val FRAME_US = 20_000L
        private const val POLL_US = 10_000L
        private const val TIMEOUT_NS = 10_000_000_000L
        private const val COMPLEXITY = 10

        /** Whether this device has an Opus encoder. */
        fun isAvailable(): Boolean = findEncoder() != null

        private fun findEncoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
            .maxByOrNull { if (it.isSoftwareOnly) 1 else 0 }

        /**
         * Opens and starts the encoder for [channels] (1 or 2) at 48 kHz and about [bitrateBps], variable bitrate and
         * the highest complexity where the codec offers them.
         *
         * @throws OpusEncoderUnavailableException when the device has no Opus encoder or it rejects the format.
         */
        override fun open(channels: Int, bitrateBps: Int): OpusFrameCodec {
            val encoder = findEncoder()
                ?: throw OpusEncoderUnavailableException("This device has no Opus encoder ($MIME)")
            val capabilities = encoder.getCapabilitiesForType(MIME)
            val format = MediaFormat.createAudioFormat(MIME, OpusOggEncoder.SAMPLE_RATE_HZ, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, capabilities.audioCapabilities?.bitrateRange?.clamp(bitrateBps) ?: bitrateBps)
            capabilities.encoderCapabilities?.let { encoderCapabilities ->
                if (encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                if (COMPLEXITY in encoderCapabilities.complexityRange) {
                    format.setInteger(MediaFormat.KEY_COMPLEXITY, COMPLEXITY)
                }
            }
            val codec = try {
                MediaCodec.createByCodecName(encoder.name)
            } catch (e: IOException) {
                throw OpusEncoderUnavailableException("Cannot create ${encoder.name}: ${e.message}", e)
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: RuntimeException) {
                codec.release()
                throw OpusEncoderUnavailableException("${encoder.name} rejected $format: ${e.message}", e)
            }
            return MediaCodecOpusFrameCodec(codec)
        }
    }
}
