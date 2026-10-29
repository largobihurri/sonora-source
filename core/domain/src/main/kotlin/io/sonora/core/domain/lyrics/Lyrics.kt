package io.sonora.core.domain.lyrics

/** One lyric line and when it starts, in milliseconds from the start of the track. Blank text is a pause. */
data class LyricLine(val timeMs: Long, val text: String)

/** A song's lyrics: timed lines that follow playback, plain text, or a note that the song has no words. */
sealed interface Lyrics {
    /** Lines sorted by start time; lines sharing a time keep their file order. */
    data class Synced(val lines: List<LyricLine>) : Lyrics {
        /** Index of the line playing at [positionMs]: the last one that started; -1 before the first. */
        fun activeLineIndex(positionMs: Long): Int {
            var low = 0
            var high = lines.lastIndex
            var found = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (lines[middle].timeMs <= positionMs) {
                    found = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return found
        }
    }

    data class Plain(val text: String) : Lyrics

    /** The song is known to be instrumental. */
    data object Instrumental : Lyrics

    companion object {
        /**
         * Lyrics from LRC or plain text: [Synced] when any line carries a `[mm:ss.xx]` stamp, else [Plain]; `null`
         * when nothing but blanks and tags is left. See [LrcParser].
         */
        fun parse(text: String): Lyrics? = LrcParser.parse(text)
    }
}

/**
 * LRC reader, tolerant of what real files carry: several stamps on one line (the line repeats at each), `[offset:]`
 * (positive shows lines earlier), ID tags such as `[ar:]` (ignored), enhanced word stamps `<mm:ss.xx>` (stripped:
 * lyrics stay line by line), Windows line endings, a byte order mark, and untimed or broken lines (skipped once any
 * line is timed).
 */
internal object LrcParser {
    private val STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val ID_TAG = Regex("""^\[([A-Za-z#]+):(.*)]$""")
    private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val SPACES = Regex("""\s+""")

    fun parse(text: String): Lyrics? {
        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()
        val untimed = mutableListOf<String>()
        for (raw in text.removePrefix("﻿").lines()) {
            var rest = raw.trim()
            val stamps = mutableListOf<Long>()
            while (true) {
                val stamp = STAMP.find(rest) ?: break
                stamps += stamp.toMillis()
                rest = rest.substring(stamp.range.last + 1)
            }
            if (stamps.isNotEmpty()) {
                val line = rest.replace(WORD_STAMP, "").replace(SPACES, " ").trim()
                stamps.forEach { timed += LyricLine(it, line) }
                continue
            }
            val tag = ID_TAG.matchEntire(rest)
            when {
                tag == null -> untimed += rest
                tag.groupValues[1].equals("offset", ignoreCase = true) ->
                    offsetMs = tag.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: offsetMs
            }
        }
        if (timed.isNotEmpty()) {
            return Lyrics.Synced(timed.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0)) }.sortedBy { it.timeMs })
        }
        val plain = untimed.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        return if (plain.isEmpty()) null else Lyrics.Plain(plain.joinToString("\n"))
    }

    private fun MatchResult.toMillis(): Long {
        val (minutes, seconds, fraction) = destructured
        // One digit is tenths, two are hundredths, three are milliseconds.
        val fractionMs = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1_000 + fractionMs
    }
}
