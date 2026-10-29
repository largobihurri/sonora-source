package io.sonora.core.data.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.MetadataTextField.ALBUM
import io.sonora.core.domain.conversion.MetadataTextField.ARTIST
import io.sonora.core.domain.conversion.MetadataTextField.BPM
import io.sonora.core.domain.conversion.MetadataTextField.COMPILATION
import io.sonora.core.domain.conversion.MetadataTextField.GENRE
import io.sonora.core.domain.conversion.MetadataTextField.INITIAL_KEY
import io.sonora.core.domain.conversion.MetadataTextField.ISRC
import io.sonora.core.domain.conversion.MetadataTextField.LABEL
import io.sonora.core.domain.conversion.MetadataTextField.TITLE
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTLEN
import org.jaudiotagger.tag.id3.framebody.FrameBodyTSSE
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Test

/** Vorbis comments, MP4 atoms and ID3 frames into the format-neutral [TrackMetadata]. */
class SourceMetadataMapperTest {

    private fun map(tag: Tag): TrackMetadata = JAudioTagger.read { SourceMetadataMapper.map(tag) }

    private fun vorbis(vararg fields: Pair<String, String>) = VorbisCommentTag.createNewTag().apply {
        setVendor("reference libFLAC 1.4.3 20230623")
        fields.forEach { (key, value) -> addField(createField(key, value)) }
    }

    @Test
    fun `Vorbis comments map by field name, repeated values joined`() {
        val metadata = map(
            vorbis(
                "TITLE" to "Señorita", "ARTIST" to "Artist A", "ARTIST" to "Artist B", "ARTIST" to "artist a",
                "ALBUM" to "LP", "GENRE" to "House", "GENRE" to "Deep House", "DATE" to "2021-05-07",
                "TRACKNUMBER" to "03", "TRACKTOTAL" to "12", "DISCNUMBER" to "1/2", "BPM" to "124.00",
                "INITIALKEY" to "8A", "ISRC" to "GBAYE0000001", "LABEL" to "Defected", "COMMENT" to "Nice",
                "LYRICS" to "Line one\r\nLine two\n", "COMPILATION" to "1",
                "REPLAYGAIN_TRACK_GAIN" to "-7.10 dB", "REPLAYGAIN_ALBUM_PEAK" to "0.98",
                "MUSICBRAINZ_TRACKID" to "abc", "ENCODER" to "Lavf60", "ENCODED-BY" to "someone",
            ),
        )

        assertThat(metadata.text).containsExactly(
            TITLE, "Señorita", ARTIST, "Artist A; Artist B", ALBUM, "LP", GENRE, "House; Deep House", BPM, "124.00",
            INITIAL_KEY, "8A", ISRC, "GBAYE0000001", LABEL, "Defected", COMPILATION, "1",
        )
        assertThat(metadata.date).isEqualTo("2021-05-07")
        assertThat(metadata.trackNumber).isEqualTo(3)
        assertThat(metadata.trackTotal).isEqualTo(12)
        assertThat(metadata.discNumber).isEqualTo(1)
        assertThat(metadata.comment).isEqualTo("Nice")
        assertThat(metadata.lyrics).isEqualTo("Line one\nLine two")
        assertThat(metadata.userText).containsExactly(
            UserTextField("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"),
            UserTextField("REPLAYGAIN_ALBUM_PEAK", "0.98"),
            UserTextField("MUSICBRAINZ_TRACKID", "abc"),
        ).inOrder()
    }

    @Test
    fun `MP4 atoms map, freeform ReplayGain is kept and iTunes technical atoms are dropped`() {
        val tag = Mp4Tag().apply {
            setField(FieldKey.TITLE, "Track")
            setField(FieldKey.ARTIST, "Artist")
            setField(FieldKey.ALBUM_ARTIST, "Album Artist")
            setField(FieldKey.TRACK, "5")
            setField(FieldKey.TRACK_TOTAL, "9")
            setField(FieldKey.YEAR, "2019")
            setField(FieldKey.KEY, "Am")
            setField(FieldKey.BPM, "128")
            addField(Mp4TagReverseDnsField("----:com.apple.iTunes:replaygain_track_gain", "com.apple.iTunes", "replaygain_track_gain", "-5.00 dB"))
            addField(Mp4TagReverseDnsField("----:com.apple.iTunes:iTunSMPB", "com.apple.iTunes", "iTunSMPB", "00000000 00000840"))
            addField(Mp4TagReverseDnsField("----:com.apple.iTunes:iTunNORM", "com.apple.iTunes", "iTunNORM", "0000"))
            setField(FieldKey.ENCODER, "iTunes 12")
        }

        val metadata = map(tag)

        assertThat(metadata.text).containsAtLeast(
            TITLE, "Track", ARTIST, "Artist", MetadataTextField.ALBUM_ARTIST, "Album Artist", INITIAL_KEY, "Am", BPM, "128",
        )
        assertThat(metadata.trackNumber).isEqualTo(5)
        assertThat(metadata.trackTotal).isEqualTo(9)
        assertThat(metadata.date).isEqualTo("2019")
        assertThat(metadata.userText).containsExactly(UserTextField("replaygain_track_gain", "-5.00 dB"))
    }

    @Test
    fun `ID3v2_4 multi-value frames are joined, TXXX kept by description, technical frames dropped`() {
        val tag = ID3v24Tag().apply {
            setField(FieldKey.TITLE, "Title")
            setField(FieldKey.ARTIST, "One")
            addField(FieldKey.ARTIST, "Two")
            setField(FieldKey.TRACK, "7")
            setField(FieldKey.DISC_NO, "2")
            setField(FieldKey.DISC_TOTAL, "3")
            setField(FieldKey.RECORD_LABEL, "Label")
            setField(FieldKey.LYRICS, "Words")
            setField(FieldKey.ENCODER, "LAME")
            setField(createField(FieldKey.CUSTOM1, "custom"))
            setFrame(ID3v24Frame("TLEN").apply { body = FrameBodyTLEN(0, "123456") })
            setFrame(ID3v24Frame("TSSE").apply { body = FrameBodyTSSE(0, "LAME 3.100") })
        }
        tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, "rel-1")

        val metadata = map(tag)

        assertThat(metadata.text[ARTIST]).isEqualTo("One; Two")
        assertThat(metadata.text[LABEL]).isEqualTo("Label")
        assertThat(metadata.trackNumber).isEqualTo(7)
        assertThat(metadata.discNumber).isEqualTo(2)
        assertThat(metadata.discTotal).isEqualTo(3)
        assertThat(metadata.lyrics).isEqualTo("Words")
        assertThat(metadata.userText).containsExactly(UserTextField("MusicBrainz Album Id", "rel-1"))
    }

    @Test
    fun `iTunes normalisation comments are not taken for the comment`() {
        val tag = ID3v23Tag().apply {
            val itunes = createField(FieldKey.COMMENT, "00000A2B 00000B3C") as org.jaudiotagger.tag.id3.AbstractID3v2Frame
            (itunes.body as org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM).description = "iTunNORM"
            addField(itunes)
            addField(createField(FieldKey.COMMENT, "Real comment"))
        }

        assertThat(map(tag).comment).isEqualTo("Real comment")
    }

    @Test
    fun `the front cover is preferred over other pictures, with its original bytes`() {
        val back = ArtworkFactory.getNew().apply { binaryData = byteArrayOf(1, 2); mimeType = "image/jpeg"; pictureType = 4 }
        val front = ArtworkFactory.getNew().apply { binaryData = byteArrayOf(3, 4); mimeType = "image/png"; pictureType = 3 }
        val tag = ID3v24Tag().apply { addField(back); addField(front) }
        val onlyBack = ID3v24Tag().apply { addField(back) }

        assertThat(JAudioTagger.read { SourceMetadataMapper.frontCover(tag) }).isEqualTo(byteArrayOf(3, 4))
        assertThat(JAudioTagger.read { SourceMetadataMapper.frontCover(onlyBack) }).isEqualTo(byteArrayOf(1, 2))
        assertThat(JAudioTagger.read { SourceMetadataMapper.frontCover(ID3v24Tag()) }).isNull()
    }

    @Test
    fun `an empty tag and a not-a-compilation flag give nothing`() {
        assertThat(map(vorbis()).isEmpty).isTrue()
        assertThat(map(vorbis("COMPILATION" to "0", "TITLE" to " ")).isEmpty).isTrue()
        assertThat(map(ID3v23Tag()).isEmpty).isTrue()
    }
}
