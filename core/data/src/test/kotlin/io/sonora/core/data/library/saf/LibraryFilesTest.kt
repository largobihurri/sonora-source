package io.sonora.core.data.library.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryFilesTest {

    @Test
    fun `folder image priority is base name first, then extension`() {
        assertThat(FolderImages.pick(listOf("front.jpg", "Folder.PNG", "COVER.png", "song.flac"))).isEqualTo("COVER.png")
        assertThat(FolderImages.pick(listOf("cover.png", "cover.jpeg", "Cover.JPG"))).isEqualTo("Cover.JPG")
        assertThat(FolderImages.pick(listOf("album.jpg", "front.png"))).isEqualTo("front.png")
        assertThat(FolderImages.pick(listOf("album.jpeg"))).isEqualTo("album.jpeg")
    }

    @Test
    fun `non candidates are ignored`() {
        assertThat(FolderImages.pick(listOf("cover.gif", "AlbumArtSmall.jpg", "scan cover.jpg", "cover", "notes.txt")))
            .isNull()
        assertThat(FolderImages.pick(emptyList())).isNull()
    }

    @Test
    fun `audio is recognized by extension, case-insensitively`() {
        for (name in listOf("a.mp3", "b.FLAC", "c.m4a", "d.aac", "e.alac", "f.ogg", "g.opus", "h.wav", "i.aif", "j.AIFF", "k.aifc")) {
            assertThat(AudioFiles.isAudio(name, null)).isTrue()
        }
        assertThat(AudioFiles.isAudio("cover.jpg", "image/jpeg")).isFalse()
        assertThat(AudioFiles.isAudio("list.m3u", "audio/x-mpegurl")).isFalse()
        assertThat(AudioFiles.isAudio("notes.txt", "text/plain")).isFalse()
    }

    @Test
    fun `audio without a known extension is recognized by mime type`() {
        assertThat(AudioFiles.isAudio("track", "audio/flac")).isTrue()
        assertThat(AudioFiles.isAudio("01. Intro", "audio/x-aiff")).isTrue()
        assertThat(AudioFiles.isAudio("track", "audio/x-mpegurl")).isFalse()
        assertThat(AudioFiles.isAudio("track", null)).isFalse()
    }

    @Test
    fun `reader extension follows the file extension, then the mime type`() {
        assertThat(AudioFiles.readerExtension("a.MP3", null)).isEqualTo("mp3")
        assertThat(AudioFiles.readerExtension("a.aif", null)).isEqualTo("aif")
        assertThat(AudioFiles.readerExtension("a.aifc", null)).isEqualTo("aifc")
        assertThat(AudioFiles.readerExtension("a.alac", null)).isEqualTo("m4a")
        assertThat(AudioFiles.readerExtension("a.oga", null)).isEqualTo("ogg")
        assertThat(AudioFiles.readerExtension("track", "audio/x-aiff")).isEqualTo("aiff")
        assertThat(AudioFiles.readerExtension("track", "audio/mp4")).isEqualTo("m4a")
        // JAudioTagger 3.0.1 has no reader for raw AAC (ADTS) or Opus.
        assertThat(AudioFiles.readerExtension("a.aac", "audio/aac")).isNull()
        assertThat(AudioFiles.readerExtension("a.opus", "audio/ogg")).isNull()
    }

    @Test
    fun `display path is the part after the volume`() {
        assertThat(SafPaths.displayPath("primary:Music/Rock")).isEqualTo("Music/Rock")
        assertThat(SafPaths.displayPath("1234-ABCD:Music/")).isEqualTo("Music")
        assertThat(SafPaths.displayPath("primary:")).isNull()
        assertThat(SafPaths.displayPath("raw")).isNull()
    }
}
