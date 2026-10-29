package io.sonora.core.cast.server

/** What a request's `Range` header asks of a file of known length (see [HttpRange.parse]). */
sealed interface RangeRequest {
    /** The whole file: no range, or one this server ignores (RFC 9110 lets a server ignore any Range). */
    data object Whole : RangeRequest

    /** Bytes [first]..[last], both inclusive and inside the file. */
    data class Part(val first: Long, val last: Long) : RangeRequest {
        val length: Long get() = last - first + 1

        /** The `Content-Range` value of a 206 answer for a file of [total] bytes. */
        fun contentRange(total: Long): String = "bytes $first-$last/$total"
    }

    /** The range starts at or past the end of the file: a 416 answer. */
    data object Unsatisfiable : RangeRequest
}

/**
 * Reads a single-range `Range: bytes=…` header, the way a Cast receiver seeks in a file (it starts with `bytes=0-`).
 * Anything this server does not serve as a part (several ranges, other units, malformed numbers, an unknown file
 * length) asks for the whole file, which RFC 9110 allows; only a well-formed range starting past the end is refused.
 */
object HttpRange {
    private const val UNIT = "bytes"

    fun parse(header: String?, length: Long): RangeRequest {
        if (header.isNullOrBlank() || length < 0) return RangeRequest.Whole
        val (unit, spec) = header.split('=', limit = 2).takeIf { it.size == 2 } ?: return RangeRequest.Whole
        if (!unit.trim().equals(UNIT, ignoreCase = true) || ',' in spec) return RangeRequest.Whole
        val bounds = spec.split('-')
        if (bounds.size != 2) return RangeRequest.Whole
        val firstText = bounds[0].trim()
        val lastText = bounds[1].trim()
        return when {
            firstText.isEmpty() -> suffix(lastText.toNonNegativeLong() ?: return RangeRequest.Whole, length)
            else -> {
                val first = firstText.toNonNegativeLong() ?: return RangeRequest.Whole
                val last = if (lastText.isEmpty()) null else lastText.toNonNegativeLong() ?: return RangeRequest.Whole
                if (last != null && last < first) return RangeRequest.Whole
                if (first >= length) return RangeRequest.Unsatisfiable
                RangeRequest.Part(first, minOf(last ?: Long.MAX_VALUE, length - 1))
            }
        }
    }

    /** `bytes=-n`: the last n bytes (all of a shorter file); none at all cannot be served. */
    private fun suffix(count: Long, length: Long): RangeRequest =
        if (count == 0L || length == 0L) RangeRequest.Unsatisfiable else RangeRequest.Part(maxOf(0L, length - count), length - 1)

    private fun String.toNonNegativeLong(): Long? = takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
}
