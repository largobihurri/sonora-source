package io.sonora.core.domain.model

/** Output formats supported by the converter. */
enum class AudioFormat(
    val displayName: String,
    val fileExtension: String,
    val mimeType: String,
    val isLossless: Boolean,
) {
    MP3("MP3", "mp3", "audio/mpeg", isLossless = false),
    AAC_M4A("AAC (M4A)", "m4a", "audio/mp4", isLossless = false),
    OPUS("Opus", "opus", "audio/ogg", isLossless = false),
    FLAC("FLAC", "flac", "audio/flac", isLossless = true),
    WAV("WAV", "wav", "audio/wav", isLossless = true),
    AIFF("AIFF", "aiff", "audio/aiff", isLossless = true),
    ;

    companion object {
        /** Resolves a format from a file extension (case-insensitive, leading dot optional). */
        fun fromExtension(extension: String): AudioFormat? {
            val normalized = extension.trim().removePrefix(".").lowercase()
            return when (normalized) {
                "aif" -> AIFF
                "aac", "mp4" -> AAC_M4A
                else -> entries.firstOrNull { it.fileExtension == normalized }
            }
        }
    }
}
