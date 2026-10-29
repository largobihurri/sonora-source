package io.sonora.core.api

/**
 * The version of the external control API: methods, query paths, their arguments and the result shapes.
 * It goes up when a caller written for an older version could break (a removed or renamed method, argument or column),
 * never for an addition.
 */
const val API_VERSION = 1

/** Limits shared by every paged query. */
object ApiLimits {
    /** Rows returned when the caller gives no `limit`. */
    const val DEFAULT_PAGE_SIZE = 50

    /** The most rows a page can hold; a larger `limit` is brought down to it. */
    const val MAX_PAGE_SIZE = 200

    /** The most track ids one command takes. */
    const val MAX_IDS = 500

    /** The longest search text, in characters. */
    const val MAX_QUERY_LENGTH = 200

    /** The longest sleep timer, in minutes (12 hours). */
    const val MAX_SLEEP_MINUTES = 720
}
