package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DuplicateQualityTest {

    private fun best(vararg copies: DuplicateCopy) = copies.sortedWith(DuplicateQuality.BEST_FIRST).map { it.uri.substringAfterLast('/') }

    @Test
    fun `lossless formats are told from lossy ones`() {
        assertThat(listOf("FLAC", "ALAC", "WAV", "AIFF", "flac").all(DuplicateQuality::isLossless)).isTrue()
        assertThat(listOf("MP3", "AAC", "Vorbis", "Opus", null).none(DuplicateQuality::isLossless)).isTrue()
    }

    @Test
    fun `lossless beats lossy whatever the bitrate`() {
        assertThat(best(mp3("mp3", kbps = 320), copy("flac", bitrateKbps = 600))).containsExactly("flac", "mp3").inOrder()
    }

    @Test
    fun `then bit depth, then sample rate, then bitrate`() {
        assertThat(best(copy("cd"), copy("hires", bitsPerSample = 24, sampleRateHz = 44_100))).containsExactly("hires", "cd").inOrder()
        assertThat(best(copy("44", bitsPerSample = 24), copy("96", bitsPerSample = 24, sampleRateHz = 96_000)))
            .containsExactly("96", "44").inOrder()
        assertThat(best(copy("deep", bitsPerSample = 24, sampleRateHz = 44_100), copy("fast", sampleRateHz = 96_000)))
            .containsExactly("deep", "fast").inOrder()
        assertThat(best(mp3("128", kbps = 128), mp3("256", kbps = 256), mp3("unknown").copy(bitrateKbps = null)))
            .containsExactly("256", "128", "unknown").inOrder()
    }

    @Test
    fun `equal quality keeps the better tagged, the one with artwork, then the older copy`() {
        assertThat(best(copy("few", tagsFilled = 2), copy("many", tagsFilled = 6))).containsExactly("many", "few").inOrder()
        assertThat(best(copy("bare", hasArtwork = false), copy("art"))).containsExactly("art", "bare").inOrder()
        assertThat(best(copy("new", dateAddedSec = 9), copy("old", dateAddedSec = 1))).containsExactly("old", "new").inOrder()
        assertThat(best(copy("b"), copy("a"))).containsExactly("a", "b").inOrder()
    }
}
