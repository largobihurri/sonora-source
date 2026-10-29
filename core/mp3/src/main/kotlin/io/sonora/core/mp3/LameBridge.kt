package io.sonora.core.mp3

/**
 * The native encoder as [Mp3Encoder] drives it; [LameJni] in the app, a fake in the JVM tests. Handles are opaque
 * native pointers. Encode and flush calls return the bytes written to `out`, or a negative status.
 */
internal interface LameBridge {

    /**
     * Sets LAME up for [sampleRateHz] and [channels] (the output keeps both), CBR at [cbrKbps] when [vbrQuality] is
     * negative, otherwise VBR at that quality; no ID3 tags; with the LAME/Xing info frame. Writes the handle into
     * `handleOut[0]` and returns [STATUS_OK], or a negative status.
     */
    fun create(sampleRateHz: Int, channels: Int, cbrKbps: Int, vbrQuality: Int, handleOut: LongArray): Int

    /** Encodes [frames] interleaved 16-bit frames of [pcm]. */
    fun encodeShorts(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int

    /** Encodes [frames] interleaved float frames of [pcm] (full scale is -1..1). */
    fun encodeFloats(handle: Long, pcm: FloatArray, frames: Int, out: ByteArray): Int

    /** Encodes what LAME still buffers and ends the stream. */
    fun flush(handle: Long, out: ByteArray): Int

    /**
     * The info frame's size in bytes (0 when there is none); copies the frame into [out] only when it fits, so a
     * call with an empty array asks for the size.
     */
    fun lametagFrame(handle: Long, out: ByteArray): Int

    fun destroy(handle: Long)

    companion object {
        const val STATUS_OK = 0
        const val STATUS_INVALID_ARGUMENT = -100
        const val STATUS_OUT_OF_MEMORY = -101
    }
}

/** JNI entry points of `libsonora_mp3.so` (src/main/cpp/lame_jni.c); loads the library on first use. */
internal object LameJni : LameBridge {
    init {
        System.loadLibrary("sonora_mp3")
    }

    external override fun create(
        sampleRateHz: Int,
        channels: Int,
        cbrKbps: Int,
        vbrQuality: Int,
        handleOut: LongArray,
    ): Int

    external override fun encodeShorts(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int

    external override fun encodeFloats(handle: Long, pcm: FloatArray, frames: Int, out: ByteArray): Int

    external override fun flush(handle: Long, out: ByteArray): Int

    external override fun lametagFrame(handle: Long, out: ByteArray): Int

    external override fun destroy(handle: Long)
}
