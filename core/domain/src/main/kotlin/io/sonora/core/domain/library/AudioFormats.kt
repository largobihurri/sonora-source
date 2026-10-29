package io.sonora.core.domain.library

import java.util.Locale

/** Short format names (`FLAC`, `MP3`...) of library files, shared by the album summary and the duplicate finder. */
object AudioFormats {
    private val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "AIFF")

    private val MIME_FORMATS = mapOf(
        "audio/flac" to "FLAC", "audio/x-flac" to "FLAC", "audio/mpeg" to "MP3", "audio/mp3" to "MP3",
        "audio/wav" to "WAV", "audio/wave" to "WAV", "audio/x-wav" to "WAV", "audio/vnd.wave" to "WAV",
        "audio/aiff" to "AIFF", "audio/x-aiff" to "AIFF", "audio/aac" to "AAC", "audio/opus" to "Opus",
        "audio/ogg" to "Vorbis", "audio/vorbis" to "Vorbis",
    )

    /** The scanner's [codec], else what the [mimeType] tells (MP4 is ambiguous: AAC or ALAC, so `null`). */
    fun of(codec: String?, mimeType: String): String? = codec ?: MIME_FORMATS[mimeType.lowercase(Locale.ROOT)]

    fun isLossless(format: String?): Boolean = format?.uppercase(Locale.ROOT) in LOSSLESS
}
