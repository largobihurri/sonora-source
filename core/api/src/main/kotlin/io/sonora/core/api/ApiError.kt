package io.sonora.core.api

/**
 * Why a call or query failed. [code] is a stable machine-readable string (part of the API); [message] is English text
 * for people and never echoes a caller-supplied value; [argument] names the argument at fault, when one is.
 */
sealed interface ApiError {
    val code: String
    val message: String
    val argument: String? get() = null

    /** The method name is not one of [ApiMethod]. */
    data class UnknownMethod(val method: String) : ApiError {
        override val code get() = "unknown_method"
        override val message get() = "Unknown method"
    }

    /** The query path is not one the API serves. */
    data object UnknownQuery : ApiError {
        override val code get() = "unknown_query"
        override val message get() = "Unknown query path"
    }

    data class MissingArgument(override val argument: String) : ApiError {
        override val code get() = "missing_argument"
        override val message get() = "Missing argument: $argument"
    }

    /** The argument is there but is not of the [expected] kind (`integer`, `number`, `boolean`, `string`, `ids`). */
    data class BadArgumentType(override val argument: String, val expected: String) : ApiError {
        override val code get() = "bad_argument_type"
        override val message get() = "Argument $argument must be $expected"
    }

    /** The argument has the right kind but a value outside what is allowed ([detail] says what is allowed). */
    data class ArgumentOutOfRange(override val argument: String, val detail: String) : ApiError {
        override val code get() = "argument_out_of_range"
        override val message get() = "Argument $argument is out of range: $detail"
    }

    /** The argument is not one of the accepted values, or clashes with another argument. */
    data class BadArgumentValue(override val argument: String, val detail: String) : ApiError {
        override val code get() = "bad_argument_value"
        override val message get() = "Argument $argument is not valid: $detail"
    }

    /** A value looks like a URI or a file path; the API takes library ids and plain text only. */
    data class LocationRejected(override val argument: String) : ApiError {
        override val code get() = "location_rejected"
        override val message get() = "Argument $argument looks like a URI or a file path, which the API never accepts"
    }

    /** No such [kind] (`album`, `artist`, `playlist`, `track`) with [id] in the library. */
    data class NotFound(val kind: String, val id: Long) : ApiError {
        override val code get() = "not_found"
        override val message get() = "No $kind with id $id"
    }

    /** The command is valid but there is nothing to do it with (no results, an empty album, no timer running). */
    data class NothingToDo(override val message: String) : ApiError {
        override val code get() = "nothing_to_do"
    }

    /** A port threw; the cause is not passed on, as it may name private paths. */
    data object Failed : ApiError {
        override val code get() = "failed"
        override val message get() = "The command failed"
    }

    /** The playback service could not be reached in time, so a command or a read of the player could not be trusted. */
    data object PlaybackUnavailable : ApiError {
        override val code get() = "playback_unavailable"
        override val message get() = "The playback service is not available"
    }

    /** The call did not finish within the time the provider allows; it may still have taken effect. */
    data object Timeout : ApiError {
        override val code get() = "timeout"
        override val message get() = "The call did not finish in time"
    }
}
