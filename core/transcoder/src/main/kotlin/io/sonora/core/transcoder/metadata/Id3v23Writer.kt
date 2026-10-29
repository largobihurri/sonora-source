package io.sonora.core.transcoder.metadata

import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.MetadataTextField.ALBUM
import io.sonora.core.domain.conversion.MetadataTextField.ALBUM_ARTIST
import io.sonora.core.domain.conversion.MetadataTextField.ALBUM_ARTIST_SORT
import io.sonora.core.domain.conversion.MetadataTextField.ALBUM_SORT
import io.sonora.core.domain.conversion.MetadataTextField.ARTIST
import io.sonora.core.domain.conversion.MetadataTextField.ARTIST_SORT
import io.sonora.core.domain.conversion.MetadataTextField.BPM
import io.sonora.core.domain.conversion.MetadataTextField.COMPILATION
import io.sonora.core.domain.conversion.MetadataTextField.COMPOSER
import io.sonora.core.domain.conversion.MetadataTextField.COMPOSER_SORT
import io.sonora.core.domain.conversion.MetadataTextField.CONDUCTOR
import io.sonora.core.domain.conversion.MetadataTextField.COPYRIGHT
import io.sonora.core.domain.conversion.MetadataTextField.GENRE
import io.sonora.core.domain.conversion.MetadataTextField.GROUPING
import io.sonora.core.domain.conversion.MetadataTextField.INITIAL_KEY
import io.sonora.core.domain.conversion.MetadataTextField.ISRC
import io.sonora.core.domain.conversion.MetadataTextField.LABEL
import io.sonora.core.domain.conversion.MetadataTextField.LANGUAGE
import io.sonora.core.domain.conversion.MetadataTextField.LYRICIST
import io.sonora.core.domain.conversion.MetadataTextField.MEDIA
import io.sonora.core.domain.conversion.MetadataTextField.ORIGINAL_ALBUM
import io.sonora.core.domain.conversion.MetadataTextField.ORIGINAL_ARTIST
import io.sonora.core.domain.conversion.MetadataTextField.ORIGINAL_YEAR
import io.sonora.core.domain.conversion.MetadataTextField.REMIXER
import io.sonora.core.domain.conversion.MetadataTextField.SUBTITLE
import io.sonora.core.domain.conversion.MetadataTextField.TITLE
import io.sonora.core.domain.conversion.MetadataTextField.TITLE_SORT
import io.sonora.core.domain.conversion.TrackMetadata
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Writes [TrackMetadata] as an ID3v2.3 tag: the version Pioneer CDJ/XDJ players and rekordbox read most reliably
 * (ID3v2.4 support on older players is partial). Hand-rolled rather than produced by JAudioTagger so the output does
 * not depend on the library's global options and holds no padding, unsynchronisation or extended header.
 *
 * - Text is ISO-8859-1 when every character fits, else UTF-16 with a little-endian byte order mark (v2.3 has no
 *   UTF-8). Text frames carry no terminator; `TXXX`/`COMM`/`USLT` descriptions are terminated.
 * - Track and disc go to `TRCK`/`TPOS` as `n` or `n/total`; the date to `TYER` (year) plus `TDAT` (`DDMM`) when it
 *   has a day; `TBPM` is the rounded integer v2.3 requires.
 * - `TSSE` names the converter; `TLEN` is never written (the source's length would be wrong after resampling).
 * - The front cover is one `APIC` frame of picture type 3, kept last so the text frames come first in the file.
 */
object Id3v23Writer {

    /** Largest tag the 28-bit syncsafe size can declare. */
    private const val MAX_TAG_BYTES = (1 shl 28) - 1

    private val TEXT_FRAMES: Map<MetadataTextField, String> = mapOf(
        TITLE to "TIT2", ARTIST to "TPE1", ALBUM_ARTIST to "TPE2", ALBUM to "TALB", GENRE to "TCON",
        COMPOSER to "TCOM", BPM to "TBPM", INITIAL_KEY to "TKEY", ISRC to "TSRC", LABEL to "TPUB",
        CONDUCTOR to "TPE3", REMIXER to "TPE4", LYRICIST to "TEXT", ORIGINAL_ARTIST to "TOPE",
        ORIGINAL_ALBUM to "TOAL", ORIGINAL_YEAR to "TORY", GROUPING to "TIT1", SUBTITLE to "TIT3",
        COPYRIGHT to "TCOP", LANGUAGE to "TLAN", MEDIA to "TMED", TITLE_SORT to "TSOT", ARTIST_SORT to "TSOP",
        ALBUM_SORT to "TSOA", ALBUM_ARTIST_SORT to "TSO2", COMPOSER_SORT to "TSOC", COMPILATION to "TCMP",
    )

    init {
        check(TEXT_FRAMES.keys == MetadataTextField.entries.toSet()) { "Every text field needs an ID3v2.3 frame" }
    }

    /** The complete tag (header included) for [metadata], with `TSSE` set to [encoder]. */
    fun write(metadata: TrackMetadata, encoder: String): ByteArray {
        val frames = ByteArrayOutputStream()
        for (field in MetadataTextField.entries) {
            val value = metadata.text[field] ?: continue
            val text = if (field == BPM) integerBpm(value) else value
            text?.let { frames.textFrame(TEXT_FRAMES.getValue(field), it) }
        }
        date(metadata.date)?.let { (year, dayMonth) ->
            frames.textFrame("TYER", year)
            dayMonth?.let { frames.textFrame("TDAT", it) }
        }
        position(metadata.trackNumber, metadata.trackTotal)?.let { frames.textFrame("TRCK", it) }
        position(metadata.discNumber, metadata.discTotal)?.let { frames.textFrame("TPOS", it) }
        frames.textFrame("TSSE", encoder)
        for (field in metadata.userText) {
            val encoding = encodingFor(field.description, field.value)
            frames.frame("TXXX", byteArrayOf(encoding.id) + encoding.terminated(field.description) + encoding.encode(field.value))
        }
        metadata.comment?.let { frames.languageFrame("COMM", it) }
        metadata.lyrics?.let { frames.languageFrame("USLT", it) }
        metadata.cover?.let { frames.picture(it) }
        val body = frames.toByteArray()
        require(body.size <= MAX_TAG_BYTES) { "ID3 tag of ${body.size} bytes exceeds the ID3v2 size limit" }
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + syncsafe(body.size) + body
    }

    private fun ByteArrayOutputStream.textFrame(id: String, value: String) {
        val encoding = encodingFor(value)
        frame(id, byteArrayOf(encoding.id) + encoding.encode(value))
    }

    /** `COMM` / `USLT`: encoding, language, empty description, text. */
    private fun ByteArrayOutputStream.languageFrame(id: String, value: String) {
        val encoding = encodingFor(value)
        frame(id, byteArrayOf(encoding.id) + LANGUAGE_CODE + encoding.terminated("") + encoding.encode(value))
    }

    private fun ByteArrayOutputStream.picture(cover: CoverArt) {
        val head = byteArrayOf(Encoding.LATIN_1.id) + cover.mimeType.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, FRONT_COVER, 0) // MIME terminator, picture type, empty description
        frame("APIC", head + cover.bytes)
    }

    private fun ByteArrayOutputStream.frame(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.ISO_8859_1))
        write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
        write(byteArrayOf(0, 0)) // flags
        write(body)
    }

    private fun syncsafe(size: Int) = byteArrayOf(
        (size ushr 21 and 0x7F).toByte(), (size ushr 14 and 0x7F).toByte(), (size ushr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
    )

    private fun position(number: Int?, total: Int?): String? = when {
        number == null -> null
        total != null && total >= number -> "$number/$total"
        else -> "$number"
    }

    /** `yyyy` and, for `yyyy-MM-dd`, `DDMM`; `null` when [date] does not start with a year. */
    private fun date(date: String?): Pair<String, String?>? {
        val match = DATE.find(date ?: return null) ?: return null
        val (year, month, day) = match.destructured
        return year to (if (month.isNotEmpty() && day.isNotEmpty()) "$day$month" else null)
    }

    private val DATE = Regex("^(\\d{4})(?:-(\\d{2})-(\\d{2}))?")

    private fun integerBpm(value: String): String? =
        value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.roundToInt()?.toString()

    private fun encodingFor(vararg values: String): Encoding =
        if (values.all { value -> value.all { it.code in 1..0xFF } }) Encoding.LATIN_1 else Encoding.UTF_16

    private enum class Encoding(val id: Byte) {
        LATIN_1(0) {
            override fun encode(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
            override val terminator = byteArrayOf(0)
        },
        UTF_16(1) {
            override fun encode(value: String): ByteArray = BOM + value.toByteArray(Charsets.UTF_16LE)
            override val terminator = byteArrayOf(0, 0)
        },
        ;

        abstract fun encode(value: String): ByteArray
        abstract val terminator: ByteArray
        fun terminated(value: String): ByteArray = encode(value) + terminator
    }

    private val BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val LANGUAGE_CODE = "eng".toByteArray(Charsets.ISO_8859_1)
    private const val FRONT_COVER: Byte = 3
}
