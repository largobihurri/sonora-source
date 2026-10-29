package io.sonora.core.cast.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastMimeTest {

    @Test
    fun `standard audio types are kept`() {
        assertThat(CastMime.audio("audio/flac", "a.flac")).isEqualTo("audio/flac")
        assertThat(CastMime.audio("audio/mpeg", "a.mp3")).isEqualTo("audio/mpeg")
    }

    @Test
    fun `aliases become the type a receiver knows`() {
        assertThat(CastMime.audio("audio/x-flac", "")).isEqualTo("audio/flac")
        assertThat(CastMime.audio("audio/x-wav", "")).isEqualTo("audio/wav")
        assertThat(CastMime.audio("audio/wave", "")).isEqualTo("audio/wav")
        assertThat(CastMime.audio("audio/mp3", "")).isEqualTo("audio/mpeg")
        assertThat(CastMime.audio("audio/x-m4a", "")).isEqualTo("audio/mp4")
        assertThat(CastMime.audio("audio/mp4a-latm", "")).isEqualTo("audio/mp4")
        assertThat(CastMime.audio("audio/x-aiff", "")).isEqualTo("audio/aiff")
        assertThat(CastMime.audio("application/ogg", "")).isEqualTo("audio/ogg")
        assertThat(CastMime.audio("AUDIO/FLAC; charset=binary", "")).isEqualTo("audio/flac")
    }

    @Test
    fun `a missing or generic type is taken from the file name`() {
        assertThat(CastMime.audio(null, "primary:Music/Song.FLAC")).isEqualTo("audio/flac")
        assertThat(CastMime.audio("audio/*", "Song.m4a")).isEqualTo("audio/mp4")
        assertThat(CastMime.audio("application/octet-stream", "Song.mp3")).isEqualTo("audio/mpeg")
        assertThat(CastMime.audio(null, "Song.opus")).isEqualTo("audio/ogg")
        assertThat(CastMime.audio(null, "Song.wav")).isEqualTo("audio/wav")
        assertThat(CastMime.audio(null, "Song.aif")).isEqualTo("audio/aiff")
        assertThat(CastMime.audio(null, "Song.aac")).isEqualTo("audio/aac")
    }

    @Test
    fun `an unknown type with an unknown name is guessed as MP3`() {
        assertThat(CastMime.audio(null, "Song")).isEqualTo("audio/mpeg")
    }

    @Test
    fun `images are recognised by their first bytes`() {
        assertThat(CastMime.image(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))).isEqualTo("image/jpeg")
        assertThat(CastMime.image(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))).isEqualTo("image/png")
        assertThat(CastMime.image("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray())).isEqualTo("image/webp")
        assertThat(CastMime.image("GIF89a".toByteArray())).isEqualTo("image/gif")
        assertThat(CastMime.image(byteArrayOf())).isEqualTo("image/jpeg")
    }
}
