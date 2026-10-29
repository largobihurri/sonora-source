package io.sonora.core.domain.library.duplicates

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * When two differently encoded files hold the same song: the same normalised title and artist, and lengths within
 * [DURATION_TOLERANCE_MS].
 *
 * Normalising folds case and accents, reads `&` as "and", drops apostrophes and other punctuation, and leaves out
 * featured artists ("feat.", "ft.", "featuring"). Bracketed or dash-separated tags that name the same recording are
 * dropped (remasters, "Explicit", "Album Version", "Original Mix", a year); every other tag stays in the title, so a
 * remix, an edit, a live take or an acoustic version never matches the plain song.
 */
object SongMatch {
    const val DURATION_TOLERANCE_MS = 2_000L

    /** Version of the normalising rules; song keys stored by an older one are worked out again. */
    const val RULES_VERSION = 1

    /** A bracketed part: `(...)`, `[...]` or `{...}`. */
    private val BRACKETED = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")
    private val DASH = Regex("\\s+[-\u2013\u2014]\\s+")
    private val FEATURING = Regex("\\b(feat|ft|featuring)\\b.*")
    private val STARTS_FEATURING = Regex("^\\s*(feat|ft|featuring)\\b")
    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['\u2018\u2019`\u00B4]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val YEAR = Regex("(19|20)\\d\\d")

    /** Words of a tag that names the same recording, not another version. */
    private val SAME_RECORDING_WORDS = setOf(
        "remaster", "remastered", "remasterizado", "remasterizada", "remasterise", "digital", "digitally",
        "explicit", "clean", "album", "lp", "original", "version", "bonus", "track",
    )
    private val SAME_RECORDING_PHRASES = setOf("original mix")

    fun title(raw: String): String {
        val folded = fold(raw)
        val versions = mutableListOf<String>()
        var base = BRACKETED.replace(folded) { match ->
            match.groupValues[1].let { tag -> if (keeps(tag)) versions += tag }
            " "
        }
        val parts = base.split(DASH)
        base = parts.first()
        parts.drop(1).filterTo(versions, ::keeps)
        base = base.replace(FEATURING, " ")
        return words(listOf(base) + versions)
    }

    fun artist(raw: String): String {
        val folded = BRACKETED.replace(fold(raw)) { match -> if (isFeaturing(match.groupValues[1])) " " else match.value }
        return words(listOf(folded.replace(FEATURING, " ")))
    }

    /** The song's normalised title and artist; `null` unless the file is tagged with both. */
    fun key(copy: DuplicateCopy): String? {
        val title = copy.tagTitle?.let(::title)?.takeIf(String::isNotEmpty) ?: return null
        val artist = copy.tagArtist?.let(::artist)?.takeIf(String::isNotEmpty) ?: return null
        return "$title\u0000$artist"
    }

    /** Known lengths no more than [DURATION_TOLERANCE_MS] apart. */
    fun sameLength(a: Long, b: Long): Boolean = a > 0 && b > 0 && abs(a - b) <= DURATION_TOLERANCE_MS

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(MARKS, "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(APOSTROPHES, "")

    /** Whether a tag stays in the title: anything but a featured artist or a same-recording tag. */
    private fun keeps(tag: String): Boolean = !isFeaturing(tag) && !isSameRecording(tag)

    private fun isFeaturing(tag: String) = STARTS_FEATURING.containsMatchIn(tag)

    private fun isSameRecording(tag: String): Boolean {
        val words = tag.split(NOT_WORD).filter(String::isNotEmpty)
        if (words.isEmpty()) return true
        return words.joinToString(" ") in SAME_RECORDING_PHRASES || words.all { it in SAME_RECORDING_WORDS || YEAR.matches(it) }
    }

    private fun words(parts: List<String>): String =
        parts.joinToString(" ").split(NOT_WORD).filter(String::isNotEmpty).joinToString(" ")
}
