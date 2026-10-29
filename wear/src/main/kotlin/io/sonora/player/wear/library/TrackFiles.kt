package io.sonora.player.wear.library

/** Which files of the music folder are songs, and what to call one that carries no title tag. */
object TrackFiles {
    /** Ogg Opus is what the phone sends; the rest are the platform's own formats, for files put there by hand. */
    val EXTENSIONS: Set<String> = setOf("opus", "ogg", "oga", "mp3", "m4a", "aac", "flac", "wav")

    /** A song file: a known audio extension, not hidden. A transfer in progress (`x.opus.part`) is not one yet. */
    fun isPlayable(fileName: String): Boolean =
        !fileName.startsWith('.') && fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** The file name without its extension and without a leading track number (`03 - Name`, `3. Name`, `1-05 Name`). */
    fun titleFromFileName(fileName: String): String {
        val withoutExtension = if (isPlayable(fileName)) fileName.substringBeforeLast('.') else fileName
        val readable = withoutExtension.replace('_', ' ').trim()
        val withoutNumber = readable.replace(LEADING_NUMBER, "").trim()
        // A name that is only numbers (`1-05`, `42`) is the title itself, not a prefix with nothing after it.
        return if (withoutNumber.any(Char::isLetter)) withoutNumber else readable.ifEmpty { fileName }
    }

    /** `03 - `, `3. `, `07 ` or `1-05 ` before the name: a track number (and disc), then a separator. */
    private val LEADING_NUMBER = Regex("""^\d{1,3}-\d{1,3}\s*[-.]?\s*(?=\D)|^\d{1,3}\s*[-.]\s*|^\d{1,3}\s+(?=\D)""")
}
