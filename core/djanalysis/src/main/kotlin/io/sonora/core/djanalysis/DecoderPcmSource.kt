package io.sonora.core.djanalysis

import io.sonora.core.djengine.pipeline.PcmSource
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * The app's decoder ([PcmDecoder], MediaCodec or the AIFF reader) as the pipeline's [PcmSource].
 *
 * The pipeline pulls blocks from a plain function, the decoder's reads are suspending calls on its own IO dispatcher:
 * each [read] waits for one block with `runBlocking` on the analysis thread (the pipeline checks for cancellation
 * before every block, so a cancelled analysis stops within one block). [restart] closes the stream and opens the uri
 * again (cheap next to a decode), and agrees only when the reopened stream has the same rate and channel count.
 * [close] releases the stream; the source is not used afterwards.
 *
 * Time base: the audio starts where the player's does. For MP3 and AAC the decoder trims the encoder delay and padding
 * the file states (LAME/Xing, iTunSMPB, MP4 edit list) exactly as Media3's player does, and the platform MP3 codec
 * drops its own 529 frames for both (D1.26, `MediaCodecPcmReader`), so a beat at t ms in the analysis is heard at t ms
 * of playback. rekordbox keeps all of those frames: exports shift with `RekordboxTimeBase` (core:rekordbox, D5).
 */
class DecoderPcmSource private constructor(
    private val decoder: PcmDecoder,
    private val sourceUri: String,
    private var stream: PcmStream,
) : PcmSource, AutoCloseable {

    private val format: PcmFormat = stream.format

    override val sampleRateHz: Int get() = format.sampleRateHz

    override val channelCount: Int get() = format.channelCount

    /** Exact when the container states it (AIFF), else from the declared duration (only drives the progress). */
    override val totalFrames: Long? =
        format.totalFrames ?: format.durationUs?.let { it * format.sampleRateHz / MICROS_PER_SECOND }?.takeIf { it > 0 }

    override fun read(buffer: FloatArray): Int = runBlocking { stream.read(buffer) }

    override fun restart(): Boolean {
        stream.close()
        val reopened = try {
            runBlocking { decoder.open(sourceUri) }
        } catch (e: IOException) {
            return false
        }
        stream = reopened
        return reopened.format.sampleRateHz == format.sampleRateHz && reopened.format.channelCount == format.channelCount
    }

    override fun close() = stream.close()

    companion object {
        /**
         * Opens [sourceUri] with [decoder].
         *
         * @throws io.sonora.core.domain.audio.PcmDecodeException when it cannot be opened or decoded.
         */
        suspend fun open(decoder: PcmDecoder, sourceUri: String): DecoderPcmSource =
            DecoderPcmSource(decoder, sourceUri, decoder.open(sourceUri))

        private const val MICROS_PER_SECOND = 1_000_000L
    }
}
