package io.sonora.core.data.lyrics

import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.lyrics.LyricLine
import io.sonora.core.domain.lyrics.Lyrics
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.Id3SupportingTag
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT

/** The file-level pieces of local lyrics: which file is a song's sidecar, its text, and ID3 synchronised lyrics. */
internal object LyricsFiles {
    private val WINDOWS_LATIN: Charset = charset("windows-1252")

    /**
     * The `.lrc` file among a folder's [names] that belongs to [audioFileName] (same base name, any case; an exact
     * match wins), or `null`.
     */
    fun sidecarName(audioFileName: String, names: Collection<String>): String? {
        val base = audioFileName.substringBeforeLast('.')
        val exact = "$base.lrc"
        return names.firstOrNull { it == exact } ?: names.firstOrNull { it.equals(exact, ignoreCase = true) }
    }

    /** Text of a lyrics file: UTF-8 or UTF-16 by byte order mark, else UTF-8, else (not valid UTF-8) Windows Latin. */
    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, WINDOWS_LATIN)
            }
        }
    }

    /**
     * Lines of an ID3 `SYLT` frame's data in [encoding] (the frame's text encoding): entries of text, its terminator
     * and a 4-byte big-endian time. Each entry is taken as one line (writers mark line starts with a leading newline,
     * which is trimmed). A truncated entry at the end is dropped.
     */
    fun syltLines(data: ByteArray, encoding: Int): List<LyricLine> {
        val wide = encoding == 1 || encoding == 2
        val charset = when (encoding) {
            1 -> Charsets.UTF_16 // each entry carries its own byte order mark
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val lines = mutableListOf<LyricLine>()
        var position = 0
        while (position < data.size) {
            val end = terminator(data, position, wide)
            if (end < 0) break
            val timeStart = end + if (wide) 2 else 1
            if (timeStart + 4 > data.size) break
            val time = ByteBuffer.wrap(data, timeStart, 4).int.toLong() and 0xFFFF_FFFFL
            val text = String(data, position, end - position, charset).trim()
            lines += LyricLine(time, text)
            position = timeStart + 4
        }
        return lines.sortedBy { it.timeMs }
    }

    /** Index of the text terminator (one zero byte, or two at an even distance for UTF-16) from [start]; -1 if none. */
    private fun terminator(data: ByteArray, start: Int, wide: Boolean): Int {
        var i = start
        while (i < data.size) {
            if (!wide && data[i] == ZERO) return i
            if (wide && i + 1 < data.size && data[i] == ZERO && data[i + 1] == ZERO) return i
            i += if (wide) 2 else 1
        }
        return -1
    }

    private const val ZERO: Byte = 0
}

/**
 * Lyrics stored in a song's tags, read with JAudioTagger (under the shared [JAudioTagger] lock): ID3 synchronised
 * lyrics (`SYLT`, millisecond stamps) first, then the lyrics field of the format (ID3 `USLT`, Vorbis `LYRICS`, MP4
 * `©lyr`) and Vorbis `UNSYNCEDLYRICS`. LRC-formatted text is read as synced lyrics.
 */
internal object EmbeddedLyrics {
    /** ID3 `SYLT` time stamp format: absolute milliseconds (the other one counts MPEG frames). */
    private const val MILLISECONDS = 2

    /** The lyrics in [file], read as a [readerKey] file; `null` when its tags hold none. Throws when unreadable. */
    fun read(file: File, readerKey: String): Lyrics? = JAudioTagger.read {
        val tag = AudioFileIO.readAs(file, readerKey).tag ?: return@read null
        synced(tag) ?: texts(tag).firstNotNullOfOrNull(Lyrics::parse)
    }

    private fun synced(tag: Tag): Lyrics? {
        val id3 = when (tag) {
            is AbstractID3v2Tag -> tag
            is Id3SupportingTag -> tag.getID3Tag()
            else -> null
        } ?: return null
        return runCatching { id3.getFields("SYLT") }.getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodySYLT }
            .filter { it.timeStampFormat == MILLISECONDS }
            .map { LyricsFiles.syltLines(it.lyrics ?: ByteArray(0), it.textEncoding.toInt()) }
            .firstOrNull { lines -> lines.any { it.text.isNotEmpty() } }
            ?.let(Lyrics::Synced)
    }

    private fun texts(tag: Tag): List<String> = listOf(
        runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull(),
        runCatching { tag.getFirst("UNSYNCEDLYRICS") }.getOrNull(),
    ).mapNotNull { text -> text?.takeIf { it.isNotBlank() } }
}
