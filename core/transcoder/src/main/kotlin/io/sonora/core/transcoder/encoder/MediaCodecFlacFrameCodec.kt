package io.sonora.core.transcoder.encoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import io.sonora.core.transcoder.flac.FlacStreamInfo
import java.io.IOException

/**
 * The platform FLAC encoder (`audio/flac`, `c2.android.flac.encoder`) driven synchronously. 16-bit output takes
 * 16-bit integers; 24-bit output asks for float input (`KEY_PCM_ENCODING`), which the encoder turns into 24-bit
 * samples, and is refused when the encoder does not accept it (it would write 16 bits). The STREAMINFO comes from the
 * codec-specific data; the frames are returned raw, the native FLAC stream is Sonora's own
 * ([FlacStreamWriter][io.sonora.core.transcoder.flac.FlacStreamWriter]).
 */
class MediaCodecFlacFrameCodec private constructor(
    private val codec: MediaCodec,
    override val floatInput: Boolean,
    private val bytesPerFrame: Int,
    private val sampleRateHz: Int,
) : FlacFrameCodec {

    private val info = MediaCodec.BufferInfo()
    private var framesQueued = 0L
    private var outputEnded = false
    private var closed = false

    override var streamInfo: ByteArray? = null
        private set

    override fun submit(pcm: ByteArray, length: Int, endOfStream: Boolean, sink: (ByteArray) -> Unit) {
        var at = 0
        while (at < length || (endOfStream && at == length && !outputEnded)) {
            val index = awaitInputBuffer(sink)
            val buffer = codec.getInputBuffer(index) ?: throw IOException("FLAC encoder gave no input buffer")
            buffer.clear()
            val n = minOf(length - at, buffer.capacity() / bytesPerFrame * bytesPerFrame)
            if (n <= 0 && at < length) throw IOException("FLAC encoder input buffer (${buffer.capacity()} bytes) holds no frame")
            buffer.put(pcm, at, n)
            at += n
            val last = endOfStream && at == length
            codec.queueInputBuffer(index, 0, n, framesQueued * 1_000_000L / sampleRateHz, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            framesQueued += n / bytesPerFrame
            drain(sink, 0)
            if (last) {
                val deadline = System.nanoTime() + TIMEOUT_NS
                while (!outputEnded) {
                    if (System.nanoTime() > deadline) throw IOException("FLAC encoder did not finish")
                    drain(sink, POLL_US)
                }
                return
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
            if (System.nanoTime() > deadline) throw IOException("FLAC encoder stopped taking input")
            drain(sink, POLL_US)
        }
    }

    /** Hands every ready frame to [sink], waiting up to [timeoutUs] for the first one. */
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
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // The stream header may also come as a plain buffer starting with the fLaC marker.
                        val header = FlacStreamInfo.fromCodecConfig(data)?.takeIf { config || data.size != FlacStreamInfo.BODY_BYTES }
                        when {
                            header != null -> if (streamInfo == null) streamInfo = header
                            !config -> sink(data)
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
        if (streamInfo != null) return
        format.getByteBuffer(CSD)?.let { buffer ->
            val data = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            streamInfo = FlacStreamInfo.fromCodecConfig(data)
        }
    }

    companion object Factory : FlacFrameCodecFactory {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_FLAC
        private const val CSD = "csd-0"
        private const val POLL_US = 10_000L
        private const val TIMEOUT_NS = 10_000_000_000L

        /** libFLAC's default: what `flac` writes without options, a good balance of size and speed on a phone. */
        private const val COMPRESSION_LEVEL = 5

        private fun findEncoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
            .maxByOrNull { if (it.isSoftwareOnly) 1 else 0 }

        /**
         * Opens and starts the encoder for [sampleRateHz] and [channels] (1 or 2): 16-bit integer input for
         * [bitsPerSample] 16, float input for 24.
         *
         * @throws FlacEncoderUnavailableException when the device has no FLAC encoder, or it refuses the rate, the
         *   channels or float input.
         */
        override fun open(sampleRateHz: Int, channels: Int, bitsPerSample: Int): FlacFrameCodec {
            require(bitsPerSample == 16 || bitsPerSample == 24) { "FLAC output is 16- or 24-bit, not $bitsPerSample" }
            val encoder = findEncoder() ?: throw FlacEncoderUnavailableException("this device has no FLAC encoder ($MIME)")
            val audio = encoder.getCapabilitiesForType(MIME).audioCapabilities
            if (audio != null && (!audio.isSampleRateSupported(sampleRateHz) || channels > audio.maxInputChannelCount)) {
                throw FlacEncoderUnavailableException("the FLAC encoder does not take $sampleRateHz Hz x $channels")
            }
            val float = bitsPerSample == 24
            val format = MediaFormat.createAudioFormat(MIME, sampleRateHz, channels)
            format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, COMPRESSION_LEVEL)
            if (float) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val codec = try {
                MediaCodec.createByCodecName(encoder.name)
            } catch (e: IOException) {
                throw FlacEncoderUnavailableException("cannot create ${encoder.name}: ${e.message}", e)
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encoding = codec.inputFormat.takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
                    ?.getInteger(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                if (float && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw FlacEncoderUnavailableException("the FLAC encoder takes no float input, so it cannot write 24-bit samples")
                }
                codec.start()
            } catch (e: FlacEncoderUnavailableException) {
                codec.release()
                throw e
            } catch (e: RuntimeException) {
                codec.release()
                throw FlacEncoderUnavailableException("${encoder.name} rejected $format: ${e.message}", e)
            }
            return MediaCodecFlacFrameCodec(codec, float, channels * if (float) 4 else 2, sampleRateHz)
        }
    }
}
