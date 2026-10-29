package io.sonora.core.api

/**
 * The outcome of a command. The provider adapter turns it into a `Bundle`: `ok`, then `error` (the [ApiError.code])
 * and `error_message` on a failure, or the [Ok.payload] entries.
 */
sealed interface ApiResult {
    val ok: Boolean

    /** [payload] values are only `String`, `Int`, `Long`, `Double` or `Boolean`, so any of them fits a `Bundle`. */
    data class Ok(val payload: Map<String, Any> = emptyMap()) : ApiResult {
        override val ok get() = true
    }

    data class Failed(val error: ApiError) : ApiResult {
        override val ok get() = false
    }
}
