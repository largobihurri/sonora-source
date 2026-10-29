package io.sonora.core.transcoder.metadata

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
import io.sonora.core.transcoder.ogg.VorbisComment

/**
 * The Vorbis comments of a FLAC file for a source's [TrackMetadata]: every text field under the name taggers use
 * (the Xiph recommendations plus the common extensions: `ALBUMARTIST`, `INITIALKEY`, sort names), track and disc
 * numbers with their totals, the date, comment, lyrics and the user's own fields (ReplayGain, MusicBrainz ids) under
 * their descriptions. The cover is not a comment in FLAC but a `PICTURE` block of its own.
 */
object FlacTagComments {

    private val NAMES: Map<MetadataTextField, String> = mapOf(
        TITLE to "TITLE", ARTIST to "ARTIST", ALBUM_ARTIST to "ALBUMARTIST", ALBUM to "ALBUM", GENRE to "GENRE",
        COMPOSER to "COMPOSER", BPM to "BPM", INITIAL_KEY to "INITIALKEY", ISRC to "ISRC", LABEL to "LABEL",
        CONDUCTOR to "CONDUCTOR", REMIXER to "REMIXER", LYRICIST to "LYRICIST", ORIGINAL_ARTIST to "ORIGINALARTIST",
        ORIGINAL_ALBUM to "ORIGINALALBUM", ORIGINAL_YEAR to "ORIGINALYEAR", GROUPING to "GROUPING", SUBTITLE to "SUBTITLE",
        COPYRIGHT to "COPYRIGHT", LANGUAGE to "LANGUAGE", MEDIA to "MEDIA", TITLE_SORT to "TITLESORT",
        ARTIST_SORT to "ARTISTSORT", ALBUM_SORT to "ALBUMSORT", ALBUM_ARTIST_SORT to "ALBUMARTISTSORT",
        COMPOSER_SORT to "COMPOSERSORT", COMPILATION to "COMPILATION",
    )

    init {
        check(NAMES.keys == MetadataTextField.entries.toSet()) { "Every text field needs a Vorbis comment name" }
    }

    fun from(metadata: TrackMetadata): List<VorbisComment> {
        val comments = ArrayList<VorbisComment>()
        val names = HashSet<String>()
        fun add(name: String, value: String?) {
            if (!value.isNullOrBlank() && names.add(name.uppercase())) comments += VorbisComment(name, value)
        }
        for (field in MetadataTextField.entries) add(NAMES.getValue(field), metadata.text[field])
        add("TRACKNUMBER", metadata.trackNumber?.toString())
        add("TRACKTOTAL", metadata.trackTotal?.toString())
        add("DISCNUMBER", metadata.discNumber?.toString())
        add("DISCTOTAL", metadata.discTotal?.toString())
        add("DATE", metadata.date)
        add("COMMENT", metadata.comment)
        add("LYRICS", metadata.lyrics)
        for (field in metadata.userText) {
            val name = field.description.uppercase()
            // A description that cannot be a comment name (`=`, non-ASCII) is dropped rather than failing the file.
            if (name.isNotEmpty() && name.all { it in ' '..'}' && it != '=' }) add(name, field.value)
        }
        return comments
    }
}
