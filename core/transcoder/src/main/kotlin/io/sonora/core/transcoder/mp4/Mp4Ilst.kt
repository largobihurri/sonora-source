package io.sonora.core.transcoder.mp4

import io.sonora.core.domain.conversion.CoverArtRule
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
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The iTunes-style `ilst` box of an M4A file for a source's [TrackMetadata] (the layout iTunes, Android's
 * MPEG4Extractor, JAudioTagger and rekordbox read): `©nam`, `©ART`, `©alb`, `aART`, `©gen`, `©day`, `trkn`, `disk`,
 * `covr` and the other iTunes atoms, each holding one `data` box (UTF-8 text, big-endian integers, JPEG or PNG), and
 * every field iTunes has no atom for as a `----` item of `com.apple.iTunes` named like its Vorbis comment, the way
 * taggers write ISRC, label and ReplayGain.
 */
object Mp4Ilst {

    private const val TYPE_IMPLICIT = 0
    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14
    private const val TYPE_INTEGER = 21
    private const val FREEFORM_MEAN = "com.apple.iTunes"

    /** Fields with an iTunes atom of their own. */
    private val ATOMS: Map<MetadataTextField, String> = mapOf(
        TITLE to "©nam", ARTIST to "©ART", ALBUM to "©alb", ALBUM_ARTIST to "aART", GENRE to "©gen",
        COMPOSER to "©wrt", GROUPING to "©grp", COPYRIGHT to "cprt", TITLE_SORT to "sonm", ARTIST_SORT to "soar",
        ALBUM_SORT to "soal", ALBUM_ARTIST_SORT to "soaa", COMPOSER_SORT to "soco",
    )

    /** Fields stored as `----:com.apple.iTunes:<name>` items. */
    private val FREEFORM: Map<MetadataTextField, String> = mapOf(
        INITIAL_KEY to "initialkey", ISRC to "ISRC", LABEL to "LABEL", CONDUCTOR to "CONDUCTOR", REMIXER to "REMIXER",
        LYRICIST to "LYRICIST", ORIGINAL_ARTIST to "ORIGINALARTIST", ORIGINAL_ALBUM to "ORIGINALALBUM",
        ORIGINAL_YEAR to "ORIGINALYEAR", SUBTITLE to "SUBTITLE", LANGUAGE to "LANGUAGE", MEDIA to "MEDIA",
    )

    init {
        check((ATOMS.keys + FREEFORM.keys + setOf(BPM, COMPILATION)) == MetadataTextField.entries.toSet()) {
            "Every text field needs an MP4 item"
        }
    }

    /** The complete `ilst` box (header included); `©too` names the converter as [encoder]. */
    fun build(metadata: TrackMetadata, encoder: String): ByteArray {
        val items = ByteArrayOutputStream()
        for (field in MetadataTextField.entries) {
            val value = metadata.text[field] ?: continue
            ATOMS[field]?.let { items.write(item(it, data(TYPE_UTF8, utf8(value)))) }
            FREEFORM[field]?.let { items.write(freeform(it, value)) }
        }
        metadata.text[BPM]?.let(::integerBpm)?.let { bpm ->
            items.write(item("tmpo", data(TYPE_INTEGER, byteArrayOf((bpm shr 8).toByte(), bpm.toByte()))))
        }
        metadata.text[COMPILATION]?.let { value ->
            if (value.trim() == "1" || value.trim().equals("true", ignoreCase = true)) items.write(item("cpil", data(TYPE_INTEGER, byteArrayOf(1))))
        }
        metadata.date?.let { items.write(item("©day", data(TYPE_UTF8, utf8(it)))) }
        metadata.trackNumber?.let { number ->
            items.write(item("trkn", data(TYPE_IMPLICIT, short(0) + short(number) + short(metadata.trackTotal ?: 0) + short(0))))
        }
        metadata.discNumber?.let { number ->
            items.write(item("disk", data(TYPE_IMPLICIT, short(0) + short(number) + short(metadata.discTotal ?: 0))))
        }
        metadata.comment?.let { items.write(item("©cmt", data(TYPE_UTF8, utf8(it)))) }
        metadata.lyrics?.let { items.write(item("©lyr", data(TYPE_UTF8, utf8(it)))) }
        for (field in metadata.userText) items.write(freeform(field.description, field.value))
        items.write(item("©too", data(TYPE_UTF8, utf8(encoder))))
        metadata.cover?.let { cover ->
            val type = if (cover.mimeType == CoverArtRule.PNG) TYPE_PNG else TYPE_JPEG
            items.write(item("covr", data(type, cover.bytes)))
        }
        return box("ilst", items.toByteArray())
    }

    private fun item(name: String, vararg children: ByteArray): ByteArray = box(name, children.fold(ByteArray(0), ByteArray::plus))

    private fun freeform(name: String, value: String): ByteArray = item(
        "----",
        box("mean", ByteArray(4) + utf8(FREEFORM_MEAN)),
        box("name", ByteArray(4) + utf8(name)),
        data(TYPE_UTF8, utf8(value)),
    )

    /** A `data` box: type indicator, locale 0, payload. */
    private fun data(type: Int, payload: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array() + payload)

    internal fun box(type: String, payload: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.ISO_8859_1)
        check(name.size == 4) { "Box types are four bytes: '$type'" }
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(name).put(payload).array()
    }

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun short(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun integerBpm(value: String): Int? =
        value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 65_536 }?.roundToInt()
}
