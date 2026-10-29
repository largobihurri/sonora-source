package io.sonora.core.data.conversion

import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.MetadataValues
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import io.sonora.core.domain.library.TagText
import java.text.Normalizer
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.aiff.AiffTag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag

/**
 * Reads a JAudioTagger [Tag] of any format (ID3v1/v2 in MP3, AIFF and WAV; RIFF INFO; Vorbis comments in FLAC and
 * Ogg; MP4 atoms) into a format-neutral [TrackMetadata]. Must run under the shared JAudioTagger lock.
 *
 * - Known fields are read through JAudioTagger's [FieldKey] mapping, every value of a multi-value field joined with
 *   [MetadataValues.join].
 * - Free-form fields (ID3 `TXXX`, unmapped Vorbis comments, MP4 `----` atoms) become [UserTextField]s: ReplayGain,
 *   MusicBrainz ids, catalogue numbers… Fields that describe the source's encoding (encoder, length, iTunes gapless
 *   and normalisation data) and every other format-specific frame are dropped.
 * - The cover is left out: [frontCover] hands its bytes over separately so images are processed outside the lock.
 */
internal object SourceMetadataMapper {

    private val TEXT_FIELDS: List<Pair<MetadataTextField, FieldKey>> = listOf(
        MetadataTextField.TITLE to FieldKey.TITLE,
        MetadataTextField.ARTIST to FieldKey.ARTIST,
        MetadataTextField.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
        MetadataTextField.ALBUM to FieldKey.ALBUM,
        MetadataTextField.GENRE to FieldKey.GENRE,
        MetadataTextField.COMPOSER to FieldKey.COMPOSER,
        MetadataTextField.BPM to FieldKey.BPM,
        MetadataTextField.INITIAL_KEY to FieldKey.KEY,
        MetadataTextField.ISRC to FieldKey.ISRC,
        MetadataTextField.LABEL to FieldKey.RECORD_LABEL,
        MetadataTextField.CONDUCTOR to FieldKey.CONDUCTOR,
        MetadataTextField.REMIXER to FieldKey.REMIXER,
        MetadataTextField.LYRICIST to FieldKey.LYRICIST,
        MetadataTextField.ORIGINAL_ARTIST to FieldKey.ORIGINAL_ARTIST,
        MetadataTextField.ORIGINAL_ALBUM to FieldKey.ORIGINAL_ALBUM,
        MetadataTextField.ORIGINAL_YEAR to FieldKey.ORIGINAL_YEAR,
        MetadataTextField.GROUPING to FieldKey.GROUPING,
        MetadataTextField.SUBTITLE to FieldKey.SUBTITLE,
        MetadataTextField.COPYRIGHT to FieldKey.COPYRIGHT,
        MetadataTextField.LANGUAGE to FieldKey.LANGUAGE,
        MetadataTextField.MEDIA to FieldKey.MEDIA,
        MetadataTextField.TITLE_SORT to FieldKey.TITLE_SORT,
        MetadataTextField.ARTIST_SORT to FieldKey.ARTIST_SORT,
        MetadataTextField.ALBUM_SORT to FieldKey.ALBUM_SORT,
        MetadataTextField.ALBUM_ARTIST_SORT to FieldKey.ALBUM_ARTIST_SORT,
        MetadataTextField.COMPOSER_SORT to FieldKey.COMPOSER_SORT,
        MetadataTextField.COMPILATION to FieldKey.IS_COMPILATION,
    )

    fun map(tag: Tag): TrackMetadata {
        val freeForm = freeFormFields(tag)
        val text = linkedMapOf<MetadataTextField, String>()
        for ((field, key) in TEXT_FIELDS) {
            val value = when (field) {
                MetadataTextField.COMPILATION -> if (isTrue(tag.first(key))) "1" else null
                // Traktor and Mixed In Key write the key as INITIALKEY in Vorbis comments.
                MetadataTextField.INITIAL_KEY -> tag.all(key) ?: freeForm.firstValue("INITIALKEY")
                else -> tag.all(key)
            }
            value?.let { text[field] = it }
        }
        return TrackMetadata(
            text = text,
            date = tag.first(FieldKey.YEAR),
            trackNumber = MetadataValues.leadingNumber(tag.first(FieldKey.TRACK)),
            trackTotal = MetadataValues.leadingNumber(tag.first(FieldKey.TRACK_TOTAL)),
            discNumber = MetadataValues.leadingNumber(tag.first(FieldKey.DISC_NO)),
            discTotal = MetadataValues.leadingNumber(tag.first(FieldKey.DISC_TOTAL)),
            comment = comment(tag),
            lyrics = multiline(runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()),
            userText = freeForm.filter { it.description.uppercase() !in NOT_USER_TEXT },
        )
    }

    /** The front cover's bytes (picture type 3), else the first picture's; `null` without pictures. */
    fun frontCover(tag: Tag): ByteArray? {
        val pictures = runCatching { tag.artworkList }.getOrNull().orEmpty()
            .filter { (it.binaryData?.size ?: 0) > 0 && !it.isLinked }
        return (pictures.firstOrNull { it.pictureType == FRONT_COVER } ?: pictures.firstOrNull())?.binaryData
    }

    /** The first comment that is not iTunes' hidden normalisation / gapless data. */
    private fun comment(tag: Tag): String? {
        val id3 = id3Of(tag) ?: return multiline(runCatching { tag.getFirst(FieldKey.COMMENT) }.getOrNull())
        return runCatching { id3.getFields(FieldKey.COMMENT) }.getOrNull().orEmpty()
            .mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodyCOMM }
            .filterNot { it.description.orEmpty().startsWith("iTun") }
            .firstNotNullOfOrNull { multiline(it.text) }
    }

    /** The ID3v2 tag behind [tag], for formats that carry one. */
    private fun id3Of(tag: Tag): AbstractID3v2Tag? = when (tag) {
        is AbstractID3v2Tag -> tag
        is AiffTag -> tag.iD3Tag
        is WavTag -> if (tag.isExistingId3Tag) tag.iD3Tag else null
        else -> null
    }

    /** `TXXX` frames, Vorbis comments and MP4 `----` atoms in tag order, one entry per description. */
    private fun freeFormFields(tag: Tag): List<UserTextField> {
        val entries = mutableListOf<Pair<String, String?>>()
        val id3 = id3Of(tag)
        val vorbis = when (tag) {
            is FlacTag -> tag.vorbisCommentTag
            is VorbisCommentTag -> tag
            else -> null
        }
        when {
            id3 != null -> id3.fieldIterator().forEach { field ->
                val body = (field as? AbstractID3v2Frame)?.body as? FrameBodyTXXX ?: return@forEach
                entries += body.description.orEmpty() to body.text?.replace('\u0000', '\n')
            }
            vorbis != null -> vorbis.fieldIterator().forEach { field ->
                if (field is TagTextField && !field.isBinary) entries += field.id to field.content
            }
            tag is Mp4Tag -> tag.fieldIterator().forEach { field ->
                if (field is Mp4TagReverseDnsField) entries += field.descriptor.orEmpty() to field.content
            }
        }
        return entries
            .mapNotNull { (key, value) -> TagText.clean(key)?.let { it to value } }
            .groupBy({ it.first.uppercase() }, { it })
            .mapNotNull { (_, values) ->
                MetadataValues.join(values.flatMap { it.second.orEmpty().split('\n') })?.let { UserTextField(values.first().first, it) }
            }
    }

    private fun List<UserTextField>.firstValue(description: String): String? =
        firstOrNull { it.description.equals(description, ignoreCase = true) }?.value

    private fun Tag.fieldIterator(): Sequence<TagField> =
        runCatching { fields }.getOrNull()?.asSequence() ?: emptySequence()

    private fun Tag.first(key: FieldKey): String? = TagText.clean(runCatching { getFirst(key) }.getOrNull())

    private fun Tag.all(key: FieldKey): String? = MetadataValues.join(
        runCatching { getAll(key) }.getOrNull().orEmpty().flatMap { it.orEmpty().split('\u0000') },
    )

    private fun isTrue(value: String?): Boolean = value?.lowercase() in setOf("1", "true", "yes")

    /** Comments and lyrics keep their line breaks: NFC, `\n` endings, other control characters dropped, trimmed. */
    private fun multiline(value: String?): String? = value
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        ?.replace("\r\n", "\n")?.replace('\r', '\n')
        ?.replace(CONTROL_EXCEPT_NEWLINE, "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private val CONTROL_EXCEPT_NEWLINE = Regex("[\\p{Cntrl}\\uFEFF&&[^\\n\\t]]")

    private const val FRONT_COVER = 3

    /**
     * Free-form names (upper case) that are not carried as `TXXX`: fields already mapped to their own frame, cover
     * blocks, and technical fields about the source's encoding.
     */
    private val NOT_USER_TEXT = setOf(
        // Mapped fields under their Vorbis comment / MP4 freeform / common TXXX names.
        "TITLE", "ARTIST", "ARTISTS", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "ALBUM", "GENRE", "COMPOSER",
        "BPM", "KEY", "INITIALKEY", "INITIAL KEY", "ISRC", "LABEL", "PUBLISHER", "ORGANIZATION", "CONDUCTOR",
        "REMIXER", "MIXARTIST", "LYRICIST", "ORIGINALARTIST", "ORIGINAL ARTIST", "ORIGINALALBUM", "ORIGINAL ALBUM",
        "ORIGINALYEAR", "ORIGINALDATE", "ORIGINAL YEAR", "GROUPING", "CONTENTGROUP", "SUBTITLE",
        "COPYRIGHT", "LANGUAGE", "MEDIA", "TITLESORT", "ARTISTSORT", "ALBUMSORT", "ALBUMARTISTSORT", "COMPOSERSORT",
        "COMPILATION", "DATE", "YEAR", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER", "DISCTOTAL",
        "TOTALDISCS", "COMMENT", "DESCRIPTION", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS",
        // Pictures.
        "METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME",
        // Technical data about the source's encoding.
        "ENCODER", "ENCODEDBY", "ENCODED BY", "ENCODED_BY", "ENCODED-BY", "ENCODING", "ENCODERSETTINGS",
        "ENCODER SETTINGS", "ENCODER_SETTINGS", "ENCODER_OPTIONS", "ENCODING PARAMS", "CODING_HISTORY", "VENDOR",
        "LENGTH", "DURATION", "TLEN", "ITUNNORM", "ITUNSMPB", "ITUNPGAP", "ITUNES_CDDB_IDS", "ITUNES_CDDB_1",
        "ITUNMOVI", "WAVEFORMATEXTENSIBLE_CHANNEL_MASK",
    )
}
