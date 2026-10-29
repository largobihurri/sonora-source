package io.sonora.core.data.library.edit

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.data.conversion.AudioFixtures
import io.sonora.core.domain.tags.ArtworkChange
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagChanges
import io.sonora.core.domain.tags.TagField
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real JAudioTagger round trips for every writable format: what is written reads back, clearing removes, and fields a
 * save does not touch stay as they were. The M4A files are 0.1-0.2 s tones made with ffmpeg (`-c:a aac` and
 * `-c:a alac`, no metadata).
 */
class TagCodecTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val codec = JAudioTaggerTagCodec

    /** A minimal JPEG-looking payload; tags store pictures as opaque bytes. */
    private val cover = Picture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4), "image/jpeg")

    private val everything = mapOf(
        TagField.TITLE to "Deliver Me",
        TagField.ARTIST to "Izyah Davis",
        TagField.ALBUM to "Dub Invasion",
        TagField.ALBUM_ARTIST to "Various Artists",
        TagField.GENRE to "Deep House",
        TagField.YEAR to "2024",
        TagField.TRACK_NUMBER to "3",
        TagField.TRACK_TOTAL to "12",
        TagField.DISC_NUMBER to "1",
        TagField.DISC_TOTAL to "2",
        TagField.BPM to "124",
        TagField.KEY to "8A",
        TagField.COMMENT to "Promo copy",
    )

    private fun file(name: String, bytes: ByteArray) = folder.newFile(name).apply { writeBytes(bytes) }

    private fun resource(name: String): File =
        file(name, checkNotNull(javaClass.getResourceAsStream("/tags/$name")) { "missing fixture $name" }.readBytes())

    private val fixtures: List<Pair<File, String>>
        get() = listOf(
            file("a.mp3", AudioFixtures.mp3()) to "mp3",
            file("a.flac", AudioFixtures.flac()) to "flac",
            file("a.wav", AudioFixtures.wav()) to "wav",
            file("a.aiff", AudioFixtures.aiff()) to "aiff",
            resource("tone-aac.m4a") to "m4a",
            resource("tone-alac.m4a") to "m4a",
        )

    @Test
    fun `every field and the artwork round-trip in every writable format`() {
        for ((file, key) in fixtures) {
            val before = codec.read(file, key)

            codec.write(file, key, TagChanges(everything, ArtworkChange.Replace(cover)))
            val after = codec.read(file, key)

            assertWithMessage(file.name).that(after.tags.fields).isEqualTo(everything)
            assertWithMessage(file.name).that(after.tags.artwork?.bytes).isEqualTo(cover.bytes)
            assertWithMessage(file.name).that(after.stream).isEqualTo(before.stream)
            assertWithMessage(file.name)
                .that(TagVerification.holds(before, after, TagChanges(everything, ArtworkChange.Replace(cover)))).isTrue()
        }
    }

    @Test
    fun `clearing removes fields and artwork and keeps the rest`() {
        for ((file, key) in fixtures) {
            codec.write(file, key, TagChanges(everything, ArtworkChange.Replace(cover)))
            val before = codec.read(file, key)
            val changes = TagChanges(mapOf(TagField.COMMENT to "", TagField.BPM to "", TagField.TITLE to "New"), ArtworkChange.Remove)

            codec.write(file, key, changes)
            val after = codec.read(file, key)

            assertWithMessage(file.name).that(after.tags.fields)
                .isEqualTo(everything - TagField.COMMENT - TagField.BPM + (TagField.TITLE to "New"))
            assertWithMessage(file.name).that(after.tags.artwork).isNull()
            assertWithMessage(file.name).that(TagVerification.holds(before, after, changes)).isTrue()
        }
    }

    @Test
    fun `an untagged file reads as empty tags`() {
        val tags = codec.read(file("plain.flac", AudioFixtures.flac()), "flac").tags

        assertThat(tags).isEqualTo(SongTags())
    }

    @Test
    fun `verification rejects a copy that does not hold the changes`() {
        val file = file("v.flac", AudioFixtures.flac())
        val before = codec.read(file, "flac")
        val changes = TagChanges(mapOf(TagField.TITLE to "New"), ArtworkChange.Replace(cover))

        assertThat(TagVerification.holds(before, before, changes)).isFalse()
        codec.write(file, "flac", TagChanges(mapOf(TagField.TITLE to "New")))
        assertThat(TagVerification.holds(before, codec.read(file, "flac"), changes)).isFalse() // no artwork
    }

    @Test
    fun `verification rejects a copy whose other fields or stream changed`() {
        val before = FileTags(SongTags(mapOf(TagField.ARTIST to "A")), StreamSignature(44_100, "2", 1_000))
        val changes = TagChanges(mapOf(TagField.TITLE to "T"))

        val lostArtist = FileTags(SongTags(mapOf(TagField.TITLE to "T")), before.stream)
        val otherStream = FileTags(SongTags(mapOf(TagField.ARTIST to "A", TagField.TITLE to "T")), before.stream.copy(sampleRateHz = 48_000))
        val fine = FileTags(SongTags(mapOf(TagField.ARTIST to "A", TagField.TITLE to "T")), before.stream)

        assertThat(TagVerification.holds(before, lostArtist, changes)).isFalse()
        assertThat(TagVerification.holds(before, otherStream, changes)).isFalse()
        assertThat(TagVerification.holds(before, fine, changes)).isTrue()
    }

    @Test
    fun `numbers compare by value and a date may be stored as its year`() {
        val before = FileTags(SongTags(), StreamSignature(44_100, "2", 1_000))
        val changes = TagChanges(mapOf(TagField.TRACK_NUMBER to "03", TagField.YEAR to "2024-05-03"))
        val stored = FileTags(SongTags(mapOf(TagField.TRACK_NUMBER to "3", TagField.YEAR to "2024")), before.stream)

        assertThat(TagVerification.holds(before, stored, changes)).isTrue()
    }
}
