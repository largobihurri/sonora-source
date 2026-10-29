package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversionInputsTest {

    @Test
    fun `the converter's source formats are taken by extension, ignoring case`() {
        val names = listOf("a.flac", "b.m4a", "c.MP3", "d.aac", "e.wav", "f.aiff", "g.AIF")
        assertThat(names.filter { ConversionInputs.isSupported(it, null) }).isEqualTo(names)
    }

    @Test
    fun `other files are not`() {
        val names = listOf("cover.jpg", "notes.txt", "a.ogg", "b.opus", "c.mid", "playlist.m3u", "noextension", "a.flac.part")
        assertThat(names.filter { ConversionInputs.isSupported(it, null) }).isEmpty()
    }

    @Test
    fun `a name without a known extension is taken by its MIME type`() {
        assertThat(ConversionInputs.isSupported("track", "audio/flac")).isTrue()
        assertThat(ConversionInputs.isSupported("track", "audio/x-aiff")).isTrue()
        assertThat(ConversionInputs.isSupported("track", "audio/mpeg")).isTrue()
        assertThat(ConversionInputs.isSupported("track", "AUDIO/WAV")).isTrue()
        assertThat(ConversionInputs.isSupported("01. Intro", "audio/flac")).isTrue()
        assertThat(ConversionInputs.isSupported("track", "audio/ogg")).isFalse()
        assertThat(ConversionInputs.isSupported("track", "image/jpeg")).isFalse()
        // A known non-audio extension wins over a generic MIME type.
        assertThat(ConversionInputs.isSupported("cover.jpg", "audio/mpeg")).isFalse()
    }

    @Test
    fun `a dotted name whose tail is no known extension is taken by its MIME type`() {
        assertThat(ConversionInputs.isSupported("Vol.2", "audio/mpeg")).isTrue()
        assertThat(ConversionInputs.isSupported("Remix.Edit", "audio/flac")).isTrue()
        assertThat(ConversionInputs.isSupported("Track.01", "audio/x-wav")).isTrue()
        assertThat(ConversionInputs.isSupported("Remix.Edit", null)).isFalse()
        assertThat(ConversionInputs.isSupported("Mix.m3u", "audio/mpeg")).isFalse()
        assertThat(ConversionInputs.isSupported("Song.flac.part", "audio/flac")).isFalse()
    }

    @Test
    fun `hidden files and macOS resource forks are skipped`() {
        assertThat(ConversionInputs.isSupported("._Song.flac", "audio/flac")).isFalse()
        assertThat(ConversionInputs.isSupported(".hidden.mp3", null)).isFalse()
    }

    @Test
    fun `files sort by path ignoring case`() {
        val paths = listOf("b/Song.flac", "A/z.mp3", "a/b.wav", "B/a.aiff")
        assertThat(ConversionInputs.sorted(paths) { it }).containsExactly("a/b.wav", "A/z.mp3", "B/a.aiff", "b/Song.flac").inOrder()
    }
}
