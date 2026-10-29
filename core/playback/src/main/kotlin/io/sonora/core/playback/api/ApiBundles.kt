package io.sonora.core.playback.api

import android.os.Bundle
import io.sonora.core.api.API_VERSION
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiResult

/**
 * The `Bundle` side of `call`: the caller's extras become the plain map the core parses, and the core's [ApiResult]
 * becomes the reply. Result keys: `ok`, `api_version`, then on a failure `error` (the stable code), `error_message`
 * and, when one is at fault, `error_argument`; on success the command's payload entries.
 */
internal object ApiBundles {
    private const val OK = "ok"
    private const val VERSION = "api_version"
    private const val ERROR = "error"
    private const val ERROR_MESSAGE = "error_message"
    private const val ERROR_ARGUMENT = "error_argument"
    private val RESERVED = setOf(OK, VERSION, ERROR, ERROR_MESSAGE, ERROR_ARGUMENT)

    /** Stands for any value that is not a plain one (a parcelable, a nested bundle): never a string nor a number. */
    private object Unsupported

    /**
     * The values a method can read. Numbers, booleans and text stay as they are (a `Float` is widened), arrays and
     * lists of them become lists, and anything else becomes [Unsupported], which the parser reports as a bad type
     * if (and only if) the method reads that argument.
     */
    fun toMap(extras: Bundle?): Map<String, Any?> {
        if (extras == null) return emptyMap()
        @Suppress("DEPRECATION") // The typed getters need the key's kind up front; this reads whatever was sent.
        return extras.keySet().associateWith { key -> plain(extras.get(key)) }
    }

    private fun plain(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Short, is Byte, is Double -> value
        is Float -> value.toDouble()
        is CharSequence -> value.toString()
        is LongArray -> value.toList()
        is IntArray -> value.toList()
        is Array<*> -> value.map(::plain)
        is List<*> -> value.map(::plain)
        else -> Unsupported
    }

    fun toBundle(result: ApiResult): Bundle = Bundle().apply {
        putBoolean(OK, result.ok)
        putInt(VERSION, API_VERSION)
        when (result) {
            is ApiResult.Ok -> result.payload.forEach { (key, value) -> if (key !in RESERVED) put(key, value) }
            is ApiResult.Failed -> {
                putString(ERROR, result.error.code)
                putString(ERROR_MESSAGE, result.error.message)
                result.error.argument?.let { putString(ERROR_ARGUMENT, it) }
            }
        }
    }

    private fun Bundle.put(key: String, value: Any) {
        when (value) {
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Double -> putDouble(key, value)
            is Boolean -> putBoolean(key, value)
            else -> putString(key, value.toString())
        }
    }
}

/** How a failed query reaches the caller: an exception whose message starts with the error's stable code. */
internal object ApiErrors {

    /**
     * A caller mistake (unknown path, bad or missing argument, nothing with that id) is an [IllegalArgumentException];
     * anything else (the command failed, timed out, or the player is unreachable) is an [IllegalStateException].
     * Both cross the binder, so a Kotlin client and `adb shell content query` see `<code>: <message>`.
     */
    fun exceptionFor(error: ApiError): RuntimeException {
        val text = "${error.code}: ${error.message}"
        return when (error) {
            is ApiError.UnknownMethod, ApiError.UnknownQuery, is ApiError.MissingArgument, is ApiError.BadArgumentType,
            is ApiError.ArgumentOutOfRange, is ApiError.BadArgumentValue, is ApiError.LocationRejected,
            is ApiError.NotFound,
            -> IllegalArgumentException(text)
            is ApiError.NothingToDo, ApiError.Failed, ApiError.PlaybackUnavailable, ApiError.Timeout ->
                IllegalStateException(text)
        }
    }
}
