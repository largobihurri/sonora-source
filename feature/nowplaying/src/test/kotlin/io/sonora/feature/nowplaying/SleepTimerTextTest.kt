package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.SleepTimerOption
import org.junit.Test

/** The sleep timer's countdown and option labels, with the module's real English and Spanish copy. */
class SleepTimerTextTest {

    @Test
    fun `the countdown reads minutes and seconds, with hours once past an hour`() {
        assertThat(SleepTimerText.countdown(900_000)).isEqualTo("15:00")
        assertThat(SleepTimerText.countdown(65_000)).isEqualTo("1:05")
        assertThat(SleepTimerText.countdown(9_000)).isEqualTo("0:09")
        assertThat(SleepTimerText.countdown(0)).isEqualTo("0:00")
        assertThat(SleepTimerText.countdown(5_400_000)).isEqualTo("1:30:00")
        assertThat(SleepTimerText.countdown(3_725_000)).isEqualTo("1:02:05")
    }

    @Test
    fun `an unknown time left shows dashes`() {
        assertThat(SleepTimerText.countdown(null)).isEqualTo("–:––")
    }

    @Test
    fun `a part second left still counts as a second`() {
        assertThat(SleepTimerText.countdown(59_001)).isEqualTo("1:00")
    }

    @Test
    fun `options are named by their minutes or by what they wait for`() {
        val english = XmlStrings.of()

        assertThat(SleepTimerText.option(SleepTimerOption.Minutes(15), english)).isEqualTo("15 Minutes")
        assertThat(SleepTimerText.option(SleepTimerOption.Minutes(1), english)).isEqualTo("1 Minute")
        assertThat(SleepTimerText.option(SleepTimerOption.EndOfSong, english)).isEqualTo("End of Current Song")
        assertThat(SleepTimerText.option(SleepTimerOption.EndOfAlbum, english)).isEqualTo("End of Current Album")
    }

    @Test
    fun `the Spanish copy pluralises the minutes`() {
        val spanish = XmlStrings.of("-es")

        assertThat(SleepTimerText.option(SleepTimerOption.Minutes(1), spanish)).isEqualTo("1 minuto")
        assertThat(SleepTimerText.option(SleepTimerOption.Minutes(45), spanish)).isEqualTo("45 minutos")
    }
}
