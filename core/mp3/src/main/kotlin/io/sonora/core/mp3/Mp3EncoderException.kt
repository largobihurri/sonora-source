package io.sonora.core.mp3

import java.io.IOException

/** A failure of the native LAME encoder; argument and state errors are IllegalArgument/IllegalState instead. */
sealed class Mp3EncoderException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** `libsonora_mp3.so` is not available for this device's ABI (the app ships arm64-v8a and x86_64 only). */
    class Unavailable(cause: Throwable) : Mp3EncoderException("MP3 encoder not available on this device", cause)

    /** LAME could not be set up with these settings, or ran out of memory doing so. */
    class InitFailed(val status: Int) : Mp3EncoderException("MP3 encoder setup failed: ${describe(status)}")

    /** LAME failed while encoding or flushing. */
    class EncodeFailed(val status: Int) : Mp3EncoderException("MP3 encoding failed: ${describe(status)}")

    private companion object {
        /** LAME's return codes (lame.h, lame_encode_buffer) and the JNI bridge's own (lame_jni.c). */
        fun describe(status: Int): String = when (status) {
            -1 -> "output buffer too small"
            -2 -> "out of memory"
            -3 -> "encoder not initialised"
            -4 -> "psychoacoustic model failed"
            LameBridge.STATUS_INVALID_ARGUMENT -> "invalid argument"
            LameBridge.STATUS_OUT_OF_MEMORY -> "out of memory"
            else -> "status $status"
        }
    }
}
