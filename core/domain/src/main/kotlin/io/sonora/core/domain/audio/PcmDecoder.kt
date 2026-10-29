package io.sonora.core.domain.audio

import java.io.IOException

/**
 * Decodes an audio file to PCM: the shared first stage of track analysis and of the converter.
 *
 * Usage, from a coroutine:
 * ```
 * decoder.open(track.uri).use { stream ->
 *     val buffer = FloatArray(PcmStream.DEFAULT_CHUNK_FRAMES * stream.format.channelCount)
 *     while (true) {
 *         val frames = stream.read(buffer)
 *         if (frames < 0) break
 *         consume(buffer, frames) // frames * channelCount interleaved samples
 *     }
 * }
 * ```
 *
 * The stream is pull-based: the consumer asks for the next block when it is ready for it, so a slow consumer (an
 * encoder, a native analyser) never makes the decoder buffer ahead, and the consumer can reuse one buffer for the
 * whole file. Both [open] and [PcmStream.read] are cancellable suspending calls that run the blocking work on the
 * implementation's IO dispatcher.
 *
 * Failures are [PcmDecodeException]s. Cancellation of the calling coroutine is reported as the coroutine's own
 * `CancellationException`, never as a [PcmDecodeException], so `catch (e: IOException)` blocks cannot swallow it.
 */
interface PcmDecoder {

    /**
     * Opens [sourceUri] (a document URI string, as in `Track.uri`) and reads as far as needed to know its
     * [PcmFormat]. The caller owns the returned stream and must [close][PcmStream.close] it.
     *
     * @throws PcmDecodeException when the source cannot be opened or decoded.
     */
    suspend fun open(sourceUri: String): PcmStream
}

/**
 * Decoded audio as interleaved 32-bit float samples in `[-1, 1)`: frame `f`, channel `c` sits at index
 * `f * channelCount + c`.
 *
 * Float32 has a 24-bit significand, so integer sources up to 24 bits convert exactly; 32-bit integer sources lose
 * their lowest 8 bits (far below any audible or analysable level). Float sources are clipped to `[-1, 1)` and NaN
 * becomes 0. Not thread-safe: read from one coroutine at a time.
 */
interface PcmStream : AutoCloseable {

    /** Known as soon as [PcmDecoder.open] returns; it does not change while reading. */
    val format: PcmFormat

    /**
     * Fills [buffer] from index 0 with up to `buffer.size / format.channelCount` whole frames and returns how many
     * frames it wrote: at least 1, or -1 once the stream is exhausted. It may return fewer frames than fit, so loop
     * until -1; the block size is bounded by [buffer].
     *
     * @throws IllegalArgumentException when [buffer] cannot hold one frame.
     * @throws IllegalStateException after [close].
     * @throws PcmDecodeException when the data cannot be decoded or the source becomes unreadable.
     */
    suspend fun read(buffer: FloatArray): Int

    /** Releases the decoder and the source. Idempotent; never throws. */
    override fun close()

    companion object {
        /** A good block size for consumers without their own preference (about 93 ms at 44.1 kHz). */
        const val DEFAULT_CHUNK_FRAMES: Int = 4096
    }
}

/** What a [PcmStream] delivers, plus what is known about the source it was decoded from. */
data class PcmFormat(
    /** Frames per second of the decoded samples (rounded when the source stores a fractional rate). */
    val sampleRateHz: Int,
    val channelCount: Int,
    /** How the source stores its samples; `null` for lossy codecs (MP3, AAC, Opus, ...) or when unknown. */
    val sourceSampleFormat: PcmSampleFormat?,
    /** Container and codec, for messages and logs: `AIFF`, `AIFF-C sowt`, `audio/flac`, `audio/mpeg`, ... */
    val codec: String,
    /** Playing time as declared by the container, when it declares one. */
    val durationUs: Long?,
    /** Exact number of frames the stream will deliver, when the source states it (AIFF); otherwise `null`. */
    val totalFrames: Long?,
)

/** Sample storage of a source file. */
enum class PcmSampleFormat(val bitsPerSample: Int, val isFloat: Boolean) {
    INT8(8, false),
    INT16(16, false),
    INT24(24, false),
    INT32(32, false),
    FLOAT32(32, true),
    FLOAT64(64, true),
}

/** Why a source could not be decoded. [IOException]s so they pass through I/O-shaped callers unchanged. */
sealed class PcmDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** No decoder for this container, codec or sample format (e.g. compressed AIFF-C, a video-only file). */
    class UnsupportedFormat(val detail: String, cause: Throwable? = null) :
        PcmDecodeException("Unsupported audio format: $detail", cause)

    /** The file is damaged or truncated, or the platform decoder failed on its data. */
    class CorruptData(val detail: String, cause: Throwable? = null) :
        PcmDecodeException("Corrupt audio data: $detail", cause)

    /** The source cannot be read: missing file, revoked permission, removed storage or another I/O error. */
    class SourceUnavailable(val sourceUri: String, cause: Throwable? = null) :
        PcmDecodeException("Cannot read $sourceUri", cause)
}
