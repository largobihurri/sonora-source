package io.sonora.core.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.AudioFixtures
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.lyrics.LyricLine
import io.sonora.core.domain.lyrics.Lyrics
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Lyrics read from real (silent) files tagged by JAudioTagger. Every lyric text is invented for the tests. */
class EmbeddedLyricsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tagged(name: String, bytes: ByteArray, readerKey: String, edit: Tag.() -> Unit): File {
        val file = folder.newFile(name).apply { writeBytes(bytes) }
        JAudioTagger.read {
            val audio = AudioFileIO.readAs(file, readerKey)
            audio.tagOrCreateAndSetDefault.edit()
            audio.commit()
        }
        return file
    }

    @Test
    fun `lrc text in a flac lyrics field reads as synced lyrics`() {
        val file = tagged("a.flac", AudioFixtures.flac(), "flac") {
            setField(FieldKey.LYRICS, "[00:01.00]First test line\n[00:02.50]Second test line")
        }

        assertThat(EmbeddedLyrics.read(file, "flac")).isEqualTo(
            Lyrics.Synced(listOf(LyricLine(1_000, "First test line"), LyricLine(2_500, "Second test line"))),
        )
    }

    @Test
    fun `a flac unsynced lyrics field reads as plain lyrics`() {
        val file = tagged("b.flac", AudioFixtures.flac(), "flac") {
            (this as FlacTag).vorbisCommentTag.setField("UNSYNCEDLYRICS", "First test line\nSecond test line")
        }

        assertThat(EmbeddedLyrics.read(file, "flac")).isEqualTo(Lyrics.Plain("First test line\nSecond test line"))
    }

    @Test
    fun `an mp3 unsynchronised lyrics frame reads as plain lyrics`() {
        val file = tagged("c.mp3", AudioFixtures.mp3(), "mp3") {
            setField(FieldKey.LYRICS, "First test line\nSecond test line")
        }

        assertThat(EmbeddedLyrics.read(file, "mp3")).isEqualTo(Lyrics.Plain("First test line\nSecond test line"))
    }

    @Test
    fun `an mp3 synchronised lyrics frame in milliseconds wins over its plain lyrics`() {
        val entries = listOf("First test line" to 1_000, "Second test line" to 3_000)
        val data = entries.fold(ByteArray(0)) { bytes, (text, ms) ->
            bytes + text.toByteArray(Charsets.ISO_8859_1) + 0.toByte() +
                byteArrayOf((ms ushr 24).toByte(), (ms ushr 16).toByte(), (ms ushr 8).toByte(), ms.toByte())
        }
        val file = tagged("d.mp3", AudioFixtures.mp3(), "mp3") {
            setField(FieldKey.LYRICS, "Plain test line")
            (this as AbstractID3v2Tag).setField(
                ID3v24Frame("SYLT").apply { body = FrameBodySYLT(0, "eng", 2, 1, "", data) },
            )
        }

        assertThat(EmbeddedLyrics.read(file, "mp3")).isEqualTo(
            Lyrics.Synced(listOf(LyricLine(1_000, "First test line"), LyricLine(3_000, "Second test line"))),
        )
    }

    @Test
    fun `a file without lyrics has none`() {
        val file = tagged("e.flac", AudioFixtures.flac(), "flac") { setField(FieldKey.TITLE, "Test") }

        assertThat(EmbeddedLyrics.read(file, "flac")).isNull()
    }
}
