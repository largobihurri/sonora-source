package io.sonora.core.cast.server

/**
 * The `Content-Type` a Cast receiver is given for a served file. The receiver picks its player from it, so library
 * types are normalised to the standard names (`audio/x-flac` -> `audio/flac`), and a missing or generic type is taken
 * from the file name's extension.
 */
object CastMime {
    private const val FALLBACK_AUDIO = "audio/mpeg"

    private val aliases = mapOf(
        "audio/x-flac" to "audio/flac",
        "audio/x-wav" to "audio/wav",
        "audio/wave" to "audio/wav",
        "audio/vnd.wave" to "audio/wav",
        "audio/mp3" to "audio/mpeg",
        "audio/x-mp3" to "audio/mpeg",
        "audio/mpeg3" to "audio/mpeg",
        "audio/x-m4a" to "audio/mp4",
        "audio/m4a" to "audio/mp4",
        "audio/mp4a-latm" to "audio/mp4",
        "audio/x-aiff" to "audio/aiff",
        "application/ogg" to "audio/ogg",
        "audio/x-ogg" to "audio/ogg",
        "audio/opus" to "audio/ogg",
        "audio/vorbis" to "audio/ogg",
    )

    private val byExtension = mapOf(
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "aifc" to "audio/aiff",
        "webm" to "audio/webm",
    )

    /** The type for an audio file whose library type is [hint] (may be null or generic) and whose name is [name]. */
    fun audio(hint: String?, name: String): String {
        val type = hint?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type.startsWith("audio/") && type != "audio/*") return aliases[type] ?: type
        if (type != null && type in aliases) return aliases.getValue(type)
        val extension = name.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return byExtension[extension] ?: FALLBACK_AUDIO
    }

    /** The type of an image from its first bytes (artwork files may carry no extension); JPEG when unknown. */
    fun image(head: ByteArray): String = when {
        head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> "image/png"
        head.startsWith('G'.code, 'I'.code, 'F'.code) -> "image/gif"
        head.size >= 12 && head.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }
}
