package io.sonora.core.domain.export

import java.text.Normalizer
import java.util.Locale

/**
 * Names that are safe on a FAT32 stick and on the players: no characters FAT forbids (the quote, asterisk, slash,
 * colon, angle brackets, question mark, backslash, bar and control characters), no trailing dots or spaces (FAT drops
 * them), no leading dot (hidden on most systems), no DOS device names (`CON`, `AUX`, `COM1`, ...), and a bounded
 * length counted in UTF-16 units, as FAT long names are.
 */
object FatNames {
    /** Longest artist or album folder name. */
    const val MAX_FOLDER_CHARS = 64

    /** Longest audio file name, extension included. */
    const val MAX_FILE_CHARS = 96

    private val FORBIDDEN = Regex("[\"*/:<>?\\\\|\\p{Cntrl}]")
    private val WHITESPACE = Regex("\\s+")
    private val DEVICE_NAMES = Regex("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?", RegexOption.IGNORE_CASE)
    private const val LONGEST_EXTENSION = 8

    /**
     * [raw] as one safe path component of at most [maxChars] UTF-16 units, or [fallback] when nothing usable is left.
     */
    fun component(raw: String?, fallback: String, maxChars: Int = MAX_FOLDER_CHARS): String =
        clean(raw)?.let { finish(cut(it, maxChars)) } ?: fallback

    /** [raw] as a safe file name of at most [maxChars] units; the extension is kept and the stem shortened instead. */
    fun fileName(raw: String, maxChars: Int = MAX_FILE_CHARS): String {
        val cleaned = clean(raw) ?: return "_"
        val extension = extensionOf(cleaned)
        val stem = cleaned.dropLast(extension.length).trimEnd('.', ' ')
        return finish(cut(stem.ifEmpty { "_" }, maxChars - extension.length)) + extension
    }

    /** The extension of [name] with its dot, or "" when the last dot is followed by more than eight characters. */
    internal fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && name.length - dot <= LONGEST_EXTENSION) name.substring(dot) else ""
    }

    /** [text] cut to at most [maxChars] UTF-16 units, never between the two halves of a surrogate pair. */
    internal fun cut(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val end = if (maxChars > 0 && Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    private fun clean(raw: String?): String? = raw
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        ?.replace('\t', ' ')
        ?.replace(FORBIDDEN, "_")
        ?.replace(WHITESPACE, " ")
        ?.trim()
        ?.trimEnd('.', ' ')
        ?.takeIf { it.isNotEmpty() }

    /** Trailing dots and spaces a cut may have exposed, a leading dot and device names. */
    private fun finish(name: String): String {
        val trimmed = name.trimEnd('.', ' ').ifEmpty { "_" }
        val visible = if (trimmed.startsWith('.')) "_" + trimmed.drop(1) else trimmed
        return if (DEVICE_NAMES.matches(visible)) "_$visible" else visible
    }
}

/**
 * Chooses where each exported audio file goes: `/Contents/<Artist>/<Album>/<file>`, with `UnknownArtist` and
 * `UnknownAlbum` for missing tags, as rekordbox does.
 *
 * FAT compares names without regard to case, so paths are compared that way too: a folder already on the stick
 * ([taken] paths, then every planned one) keeps its first spelling, and a file whose name is taken gets " (2)",
 * " (3)", ... before its extension. A planned path is never longer than [MAX_PATH_CHARS].
 */
class StickPathPlanner(taken: Collection<String> = emptyList()) {

    /** Every reserved path, upper-cased. */
    private val reserved = HashSet<String>()

    /** The first spelling of every folder seen, by its upper-cased path. */
    private val folders = HashMap<String, String>()

    init {
        taken.forEach(::reserve)
    }

    /**
     * Reserves and returns the path, from the stick root with a leading `/`, for [fileName] by [artist] on [album].
     * [exists] reports files already on the stick (by the same kind of path) that are not in [taken], so a file the
     * export does not own is never overwritten.
     */
    fun plan(artist: String?, album: String?, fileName: String, exists: (String) -> Boolean = { false }): String {
        val artistFolder = folder("/$CONTENTS", FatNames.component(artist, UNKNOWN_ARTIST))
        val albumFolder = folder(artistFolder, FatNames.component(album, UNKNOWN_ALBUM))
        val name = FatNames.fileName(fileName)
        var number = 1
        while (true) {
            val candidate = "$albumFolder/${numbered(name, number)}"
            if (key(candidate) !in reserved && !exists(candidate)) {
                reserve(candidate)
                return candidate
            }
            number++
        }
    }

    /** [parent]/[name], spelt as the folder already is when it is known. */
    private fun folder(parent: String, name: String): String {
        val path = "$parent/$name"
        return folders.getOrPut(key(path)) { path }
    }

    private fun reserve(path: String) {
        reserved += key(path)
        var end = path.lastIndexOf('/')
        // Record each parent folder's spelling, deepest first, unless an earlier path already fixed it.
        while (end > 0) {
            val folder = path.substring(0, end)
            folders.putIfAbsent(key(folder), folder)
            end = folder.lastIndexOf('/')
        }
    }

    /** [name] (a [FatNames.fileName]) with " ([number])" before the extension that [FatNames.fileName] kept. */
    private fun numbered(name: String, number: Int): String {
        if (number == 1) return name
        val extension = FatNames.extensionOf(name)
        val suffix = " ($number)"
        val budget = (FatNames.MAX_FILE_CHARS - suffix.length - extension.length).coerceAtLeast(1)
        val stem = FatNames.cut(name.dropLast(extension.length), budget)
        return stem + suffix + extension
    }

    private fun key(path: String): String = path.uppercase(Locale.ROOT)

    companion object {
        const val CONTENTS = "Contents"
        const val UNKNOWN_ARTIST = "UnknownArtist"
        const val UNKNOWN_ALBUM = "UnknownAlbum"

        /** `/Contents/` + two folders + a file at their maximum lengths. */
        const val MAX_PATH_CHARS = 1 + CONTENTS.length + 1 + 2 * (FatNames.MAX_FOLDER_CHARS + 1) + FatNames.MAX_FILE_CHARS
    }
}
