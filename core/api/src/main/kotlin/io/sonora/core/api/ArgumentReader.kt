package io.sonora.core.api

import kotlin.math.floor

/** Thrown inside the parsers to leave with a typed [error]; never escapes them. */
internal class ApiFailure(val error: ApiError) : RuntimeException(error.message, null, false, false)

/**
 * Whether [text] looks like a URI or a file path: a scheme, a `//`-style authority, an absolute, home or Windows path,
 * or a `..` segment. The API takes library ids and plain text, so such a value is refused wherever it appears.
 * A plain search such as `AC/DC` or `Artist: Song` is not a location.
 */
internal fun looksLikeLocation(text: String): Boolean {
    val value = text.trim()
    if (value.contains("://")) return true
    if (KNOWN_SCHEME.containsMatchIn(value) || WINDOWS_DRIVE.containsMatchIn(value)) return true
    if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("~/")) return true
    return value.split('/', '\\').any { it == ".." }
}

private val KNOWN_SCHEME = Regex("^(content|file|android\\.resource|http|https|ftp|data|intent|jar):", RegexOption.IGNORE_CASE)
private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

/** Throws [ApiFailure] for the first string anywhere in [extras] that looks like a location. */
internal fun rejectLocations(extras: Map<String, Any?>) {
    for ((name, value) in extras) {
        if (containsLocation(value)) throw ApiFailure(ApiError.LocationRejected(name))
    }
}

private fun containsLocation(value: Any?): Boolean = when (value) {
    is String -> looksLikeLocation(value)
    is Iterable<*> -> value.any { containsLocation(it) }
    is Array<*> -> value.any { containsLocation(it) }
    else -> false
}

/** Reads typed arguments from a method's extras (or a query's parameters), throwing [ApiFailure] on a bad one. */
internal class ArgumentReader(private val extras: Map<String, Any?>) {

    fun has(name: String): Boolean = extras[name] != null

    fun string(name: String): String =
        stringOrNull(name) ?: throw ApiFailure(ApiError.MissingArgument(name))

    fun stringOrNull(name: String): String? = when (val value = extras[name]) {
        null -> null
        is String -> value
        else -> throw ApiFailure(ApiError.BadArgumentType(name, "string"))
    }

    fun long(name: String): Long = longOrNull(name) ?: throw ApiFailure(ApiError.MissingArgument(name))

    fun longOrNull(name: String): Long? = extras[name]?.let { whole(it) ?: throw ApiFailure(ApiError.BadArgumentType(name, "integer")) }

    /** A whole number in [range]; [default] stands in when the argument is absent (a required one when it is null). */
    fun int(name: String, range: IntRange, default: Int? = null): Int {
        val value = longOrNull(name) ?: default?.toLong() ?: throw ApiFailure(ApiError.MissingArgument(name))
        if (value !in range.first.toLong()..range.last.toLong()) {
            throw ApiFailure(ApiError.ArgumentOutOfRange(name, "${range.first}..${range.last}"))
        }
        return value.toInt()
    }

    /** A library id: a positive whole number. */
    fun id(name: String): Long {
        val value = long(name)
        if (value <= 0) throw ApiFailure(ApiError.ArgumentOutOfRange(name, "ids are positive"))
        return value
    }

    fun boolean(name: String, default: Boolean? = null): Boolean = when (val value = extras[name]) {
        null -> default ?: throw ApiFailure(ApiError.MissingArgument(name))
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw ApiFailure(ApiError.BadArgumentType(name, "boolean"))
        }
        else -> throw ApiFailure(ApiError.BadArgumentType(name, "boolean"))
    }

    /** A finite number. */
    fun number(name: String): Double {
        val value = when (val raw = extras[name]) {
            null -> throw ApiFailure(ApiError.MissingArgument(name))
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        if (value == null || !value.isFinite()) throw ApiFailure(ApiError.BadArgumentType(name, "number"))
        return value
    }

    /**
     * One to [ApiLimits.MAX_IDS] library ids, as a list, an array, a single number or comma separated text (all a
     * shell can send), in the order given.
     */
    fun ids(name: String): List<Long> {
        val raw = extras[name] ?: throw ApiFailure(ApiError.MissingArgument(name))
        val parts: List<Any?> = when (raw) {
            is Iterable<*> -> raw.toList()
            is Array<*> -> raw.toList()
            is LongArray -> raw.toList()
            is IntArray -> raw.toList()
            is Number -> listOf(raw)
            is String -> if (raw.isBlank()) emptyList() else raw.split(',')
            else -> throw ApiFailure(ApiError.BadArgumentType(name, "ids"))
        }
        if (parts.isEmpty()) throw ApiFailure(ApiError.ArgumentOutOfRange(name, "at least one id"))
        if (parts.size > ApiLimits.MAX_IDS) {
            throw ApiFailure(ApiError.ArgumentOutOfRange(name, "at most ${ApiLimits.MAX_IDS} ids"))
        }
        val ids = parts.map { whole(it) ?: throw ApiFailure(ApiError.BadArgumentType(name, "ids")) }
        if (ids.any { it <= 0 }) throw ApiFailure(ApiError.ArgumentOutOfRange(name, "ids are positive"))
        return ids
    }

    private fun whole(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Number -> value.toDouble().takeIf { it.isFinite() && floor(it) == it && it in LONG_RANGE }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private companion object {
        val LONG_RANGE = -9.2e18..9.2e18
    }
}
