package io.sonora.core.domain.conversion.batch

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Names the converter writes. Outputs often end up on FAT/exFAT USB sticks, which reject `" * / : < > ? \ |` and
 * control characters, ignore case and drop trailing dots and spaces; every name here is safe there, and short enough
 * for both those sticks and internal storage ([MAX_NAME_BYTES]).
 */
object BatchNaming {

    /** The folder holding every batch folder, created in the user's chosen output location. */
    const val CONVERTED_FOLDER: String = "Convertidos"

    /** Suffix of files still being written; they are renamed once verified and deleted when the batch stops. */
    const val PARTIAL_SUFFIX: String = ".part"

    /**
     * Longest name, in UTF-8 bytes. FAT and exFAT allow 255 UTF-16 units per name, ext4 and F2FS (internal storage,
     * where the output folder may be) 255 bytes. A character never takes fewer UTF-8 bytes than UTF-16 units, so a name
     * within 255 bytes fits both.
     */
    const val MAX_NAME_BYTES: Int = 255

    // A dot instead of ':' between hours and minutes: ':' is illegal on FAT.
    private val FOLDER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")
    private const val FAT_ILLEGAL = "\"*/:<>?\\|"

    /** Room for a character and a number of up to ten digits, " (1234567890)". */
    private const val MIN_NAME_BYTES = 14

    /** The dated folder of a batch created at [createdAtMs] in [zone], e.g. "2026-10-03 14.05". */
    fun batchFolderName(createdAtMs: Long, zone: ZoneId): String =
        FOLDER_DATE.format(Instant.ofEpochMilli(createdAtMs).atZone(zone))

    /** Bytes [name] takes in UTF-8, as ext4 and F2FS count it (an unpaired surrogate counts as three). */
    fun byteLength(name: String): Int {
        var bytes = 0
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            bytes += utf8Bytes(codePoint)
            i += Character.charCount(codePoint)
        }
        return bytes
    }

    /**
     * [name] with FAT-illegal characters replaced by `_` and trailing dots and spaces removed, shortened to
     * [maxBytes] before its extension; never empty.
     */
    fun fatSafe(name: String, maxBytes: Int = MAX_NAME_BYTES): String {
        val replaced = name.map { if (it < ' ' || it in FAT_ILLEGAL) '_' else it }.joinToString("")
        return fit(replaced.trimEnd('.', ' ').ifEmpty { "_" }, maxBytes)
    }

    /**
     * [desired] (shortened to [maxBytes]) if no name in [taken] matches it ignoring case, else "Name (2).ext",
     * "Name (3).ext", ... with the name shortened so the number still fits. An extension too long to leave room for
     * the number is shortened with the name: "Name.longextension (2)".
     */
    fun uniqueFileName(desired: String, taken: Collection<String>, maxBytes: Int = MAX_NAME_BYTES): String {
        require(maxBytes >= MIN_NAME_BYTES) { "No room for a numbered name in $maxBytes bytes" }
        val fitted = fit(desired, maxBytes)
        val (stem, extension) = split(fitted)
        return unique(fitted, taken) { n ->
            val number = " ($n)"
            if (byteLength(number + extension) < maxBytes) {
                shorten(stem, maxBytes - byteLength(number + extension)) + number + extension
            } else {
                shorten(fitted, maxBytes - number.length) + number
            }
        }
    }

    /** Like [uniqueFileName] for folders, whose names may contain dots: "2026-10-03 14.05 (2)". */
    fun uniqueFolderName(desired: String, taken: Collection<String>): String =
        unique(desired, taken) { n -> "$desired ($n)" }

    /** [name] shortened before its extension to at most [maxBytes] UTF-8 bytes. */
    fun fit(name: String, maxBytes: Int = MAX_NAME_BYTES): String {
        if (byteLength(name) <= maxBytes) return name
        val (stem, extension) = split(name)
        if (byteLength(extension) >= maxBytes) return shorten(name, maxBytes)
        return shorten(stem, maxBytes - byteLength(extension)) + extension
    }

    /** The longest start of [text] within [maxBytes], whole characters only, not ending with a dot or space. */
    private fun shorten(text: String, maxBytes: Int): String {
        if (byteLength(text) <= maxBytes) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            bytes += utf8Bytes(codePoint)
            if (bytes > maxBytes) break
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end).trimEnd('.', ' ').ifEmpty { "_" }
    }

    private fun utf8Bytes(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** "Name.ext" into "Name" and ".ext"; a name without a dot (or only a leading one) has no extension. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private inline fun unique(desired: String, taken: Collection<String>, numbered: (Int) -> String): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        if (desired.lowercase() !in used) return desired
        var n = 2
        while (numbered(n).lowercase() in used) n++
        return numbered(n)
    }
}
