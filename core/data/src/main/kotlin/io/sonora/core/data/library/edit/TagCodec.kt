package io.sonora.core.data.library.edit

import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.tags.ArtworkChange
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagChanges
import io.sonora.core.domain.tags.TagField
import java.io.File
import kotlin.math.abs
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField as JTagField
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory

/** Audio stream facts a tag rewrite must leave alone. */
internal data class StreamSignature(val sampleRateHz: Int?, val channels: String?, val lengthMs: Long?)

/** A file's editable tags and its stream, as read from a local file. */
internal data class FileTags(val tags: SongTags, val stream: StreamSignature)

/** Reads and writes the editable tags of a local file, given its JAudioTagger reader key (`mp3`, `flac`, ...). */
internal interface TagCodec {
    /** Throws when the file cannot be parsed. */
    fun read(file: File, readerKey: String): FileTags

    /** Applies [changes] to [file] in place; throws when the format refuses one of them. */
    fun write(file: File, readerKey: String, changes: TagChanges)
}

/**
 * [TagCodec] over JAudioTagger 3.0.1, through the shared [JAudioTagger] lock. New tags take the format's default
 * (ID3v2.3 for MP3, ID3 chunks for WAV and AIFF, Vorbis comments for FLAC, iTunes atoms for M4A).
 */
internal object JAudioTaggerTagCodec : TagCodec {

    /** Reader keys whose tags are edited: MP3, FLAC, M4A (AAC and ALAC), WAV and AIFF. */
    val WRITABLE = setOf("mp3", "flac", "m4a", "wav", "aif", "aiff", "aifc")

    private val KEYS = mapOf(
        TagField.TITLE to FieldKey.TITLE,
        TagField.ARTIST to FieldKey.ARTIST,
        TagField.ALBUM to FieldKey.ALBUM,
        TagField.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
        TagField.GENRE to FieldKey.GENRE,
        TagField.YEAR to FieldKey.YEAR,
        TagField.TRACK_NUMBER to FieldKey.TRACK,
        TagField.TRACK_TOTAL to FieldKey.TRACK_TOTAL,
        TagField.DISC_NUMBER to FieldKey.DISC_NO,
        TagField.DISC_TOTAL to FieldKey.DISC_TOTAL,
        TagField.BPM to FieldKey.BPM,
        TagField.KEY to FieldKey.KEY,
        TagField.COMMENT to FieldKey.COMMENT,
    )

    /** ID3/FLAC picture type "Cover (front)". */
    private const val FRONT_COVER = 3

    override fun read(file: File, readerKey: String): FileTags = JAudioTagger.read {
        val audio = AudioFileIO.readAs(file, readerKey)
        val tag: Tag? = audio.tag
        val fields = KEYS.mapNotNull { (field, key) ->
            tag?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }?.let { field to it }
        }.toMap()
        val header = audio.audioHeader
        FileTags(
            tags = SongTags(fields, tag?.firstArtwork?.let(::picture)),
            stream = StreamSignature(
                sampleRateHz = header?.let { runCatching { it.sampleRateAsNumber }.getOrNull() },
                channels = header?.let { runCatching { it.channels }.getOrNull() },
                lengthMs = header?.let { runCatching { (it.preciseTrackLength * 1000).toLong() }.getOrNull() },
            ),
        )
    }

    override fun write(file: File, readerKey: String, changes: TagChanges) = JAudioTagger.read {
        val audio = AudioFileIO.readAs(file, readerKey)
        val tag = audio.tagOrCreateAndSetDefault
        for ((field, value) in changes.fields) {
            val key = KEYS.getValue(field)
            if (value.isEmpty()) tag.deleteField(key) else tag.setField(key, value)
        }
        when (val artwork = changes.artwork) {
            ArtworkChange.Keep -> Unit
            ArtworkChange.Remove -> tag.deleteArtworkField()
            is ArtworkChange.Replace -> {
                tag.deleteArtworkField()
                tag.setField(artworkField(tag, artwork.picture))
            }
        }
        audio.commit()
    }

    /** FLAC builds its picture block itself: its generic path needs desktop image decoding, absent on Android. */
    private fun artworkField(tag: Tag, picture: Picture): JTagField {
        val mimeType = picture.mimeType ?: "image/jpeg"
        if (tag is FlacTag) {
            return tag.createArtworkField(picture.bytes, FRONT_COVER, mimeType, "", picture.width ?: 0, picture.height ?: 0, 0, 0)
        }
        val artwork = ArtworkFactory.getNew().apply {
            binaryData = picture.bytes
            this.mimeType = mimeType
            pictureType = FRONT_COVER
            description = ""
        }
        return tag.createField(artwork)
    }

    private fun picture(artwork: org.jaudiotagger.tag.images.Artwork): Picture? {
        val bytes = artwork.binaryData?.takeIf { it.isNotEmpty() } ?: return null
        return Picture(
            bytes = bytes,
            mimeType = artwork.mimeType?.takeIf { it.isNotBlank() },
            width = artwork.width.takeIf { it > 0 },
            height = artwork.height.takeIf { it > 0 },
        )
    }
}

/** Whether [after], read back from a rewritten copy, holds [changes] on top of [before] and the same stream. */
internal object TagVerification {
    /** Largest stream length difference tolerated: header estimates may move by a frame, never by more. */
    private const val LENGTH_TOLERANCE_MS = 50L

    fun holds(before: FileTags, after: FileTags, changes: TagChanges): Boolean {
        val expected = TagField.entries.associateWith { field ->
            changes.fields[field] ?: before.tags.fields[field].orEmpty()
        }
        val fieldsHold = expected.all { (field, value) -> same(field, value, after.tags.fields[field].orEmpty()) }
        val artworkHolds = when (val artwork = changes.artwork) {
            ArtworkChange.Keep -> before.tags.artwork?.bytes.contentEquals(after.tags.artwork?.bytes)
            ArtworkChange.Remove -> after.tags.artwork == null
            is ArtworkChange.Replace -> artwork.picture.bytes.contentEquals(after.tags.artwork?.bytes)
        }
        return fieldsHold && artworkHolds && sameStream(before.stream, after.stream)
    }

    /**
     * Numbers compare by value ("03" is stored as 3 by some formats) and a date may come back as its year alone
     * (ID3v2.3 keeps only the year in TYER); anything else must match exactly.
     */
    private fun same(field: TagField, expected: String, stored: String): Boolean = when {
        expected == stored -> true
        field.numeric -> expected.toIntOrNull() != null && expected.toIntOrNull() == stored.toIntOrNull()
        field == TagField.YEAR -> stored.length == 4 && expected.startsWith(stored)
        else -> false
    }

    private fun sameStream(before: StreamSignature, after: StreamSignature): Boolean =
        before.sampleRateHz == after.sampleRateHz && before.channels == after.channels &&
            (before.lengthMs == null || after.lengthMs != null && abs(before.lengthMs - after.lengthMs) <= LENGTH_TOLERANCE_MS)
}
