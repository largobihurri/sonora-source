package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackTempo
import java.util.Locale
import org.junit.Test

class PlaybackTempoTextTest {

    @Test
    fun `speeds show up to two decimals and a times sign`() {
        assertThat(PlaybackTempoText.speed(PlaybackTempo.ofPercent(125), Locale.US)).isEqualTo("1.25×")
        assertThat(PlaybackTempoText.speed(PlaybackTempo.ofPercent(150), Locale.US)).isEqualTo("1.5×")
        assertThat(PlaybackTempoText.speed(PlaybackTempo.ofPercent(100), Locale.US)).isEqualTo("1×")
        assertThat(PlaybackTempoText.speed(PlaybackTempo.ofPercent(200), Locale.US)).isEqualTo("2×")
    }

    @Test
    fun `speeds use the locale's decimal mark`() {
        assertThat(PlaybackTempoText.speed(PlaybackTempo.ofPercent(75), Locale.forLanguageTag("es-ES"))).isEqualTo("0,75×")
    }

    @Test
    fun `semitones are signed, with a true minus sign`() {
        assertThat(PlaybackTempoText.semitones(2)).isEqualTo("+2")
        assertThat(PlaybackTempoText.semitones(-12)).isEqualTo("−12")
        assertThat(PlaybackTempoText.semitones(0)).isEqualTo("0")
    }

    @Test
    fun `the badge shows the speed and any pitch change`() {
        assertThat(PlaybackTempoText.badge(PlaybackTempo.ofPercent(125, 2), Locale.US)).isEqualTo("1.25× · +2")
        assertThat(PlaybackTempoText.badge(PlaybackTempo.ofPercent(80, 0), Locale.US)).isEqualTo("0.8×")
        assertThat(PlaybackTempoText.badge(PlaybackTempo.ofPercent(100, -3), Locale.US)).isEqualTo("1× · −3")
    }
}
