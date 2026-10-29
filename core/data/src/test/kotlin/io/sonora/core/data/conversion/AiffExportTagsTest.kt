package io.sonora.core.data.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Genre, comment and cover of AIFF files as the CDJ export reads them (through the conversion's source reader, real
 * JAudioTagger), for ID3 chunks written by JAudioTagger and by other taggers (ID3v2.3, numeric or text genres). The
 * XDJ-700 showed no genre for an exported AIFF (C24): these pin down that the reader keeps it. (JAudioTagger 3.0.1 does
 * not read an ID3 chunk named `id3 ` in lower case, which TagLib accepts: such a file loses every tag, not only its
 * genre.)
 */
class AiffExportTagsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val reader by lazy {
        SourceMetadataFileReader(folder.newFolder("cache"), prepareCover = { CoverArt(it, "image/jpeg", 1, 1) })
    }

    private fun read(file: File): TrackMetadata? = reader.read("aiff", direct = null) { out -> file.inputStream().use { it.copyTo(out) } }

    @Test
    fun `an AIFF tagged by JAudioTagger gives its genre, comment and cover`() {
        val file = folder.newFile("02 Dub Warrior.aiff").apply { writeBytes(AudioFixtures.aiff()) }
        val picture = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { it.toByte() }
        JAudioTagger.read {
            val audio = AudioFileIO.readAs(file, "aiff")
            audio.tagOrCreateAndSetDefault.apply {
                setField(FieldKey.TITLE, "Dub Warrior")
                setField(FieldKey.ARTIST, "Aryeh Yah")
                setField(FieldKey.GENRE, "Dub")
                setField(FieldKey.COMMENT, "Visit https://example.bandcamp.com")
                setField(ArtworkFactory.getNew().apply { binaryData = picture; mimeType = "image/jpeg"; pictureType = 3 })
            }
            audio.commit()
        }

        val metadata = read(file)!!

        assertThat(metadata.text[MetadataTextField.GENRE]).isEqualTo("Dub")
        assertThat(metadata.comment).isEqualTo("Visit https://example.bandcamp.com")
        assertThat(metadata.cover?.bytes).isEqualTo(picture)
    }

    @Test
    fun `an ID3v2-3 chunk with a numeric genre gives the genre's name`() {
        val file = folder.newFile("numeric.aiff").apply { writeBytes(aiffWithId3("ID3 ", id3v23("TIT2" to "Song", "TCON" to "(16)"))) }

        assertThat(read(file)!!.text[MetadataTextField.GENRE]).isEqualTo("Reggae")
    }

    @Test
    fun `an ID3v2-3 chunk written by another tagger gives its text genre`() {
        val file = folder.newFile("text.aiff").apply { writeBytes(aiffWithId3("ID3 ", id3v23("TIT2" to "Song", "TCON" to "Dub"))) }

        val metadata = read(file)!!

        assertThat(metadata.text[MetadataTextField.TITLE]).isEqualTo("Song")
        assertThat(metadata.text[MetadataTextField.GENRE]).isEqualTo("Dub")
    }

    /** [AudioFixtures.aiff] with an ID3 chunk named [chunkId] holding [id3] after the sound data. */
    private fun aiffWithId3(chunkId: String, id3: ByteArray): ByteArray {
        val plain = AudioFixtures.aiff()
        val padded = if (id3.size % 2 == 1) id3 + 0 else id3
        val body = plain.copyOfRange(8, plain.size) + chunkId.toByteArray() + be32(id3.size) + padded
        return "FORM".toByteArray() + be32(body.size) + body
    }

    /** An ID3v2.3 tag of ISO-8859-1 text frames. */
    private fun id3v23(vararg frames: Pair<String, String>): ByteArray {
        val content = ByteArrayOutputStream().apply {
            for ((id, text) in frames) {
                val value = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
                write(id.toByteArray()); write(be32(value.size)); write(byteArrayOf(0, 0)); write(value)
            }
        }.toByteArray()
        val size = content.size
        val syncsafe = byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe + content
    }

    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
}
