package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.TempoRange
import java.util.Locale
import org.junit.Test

class DjTempoTextTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val eu = Locale.forLanguageTag("eu")

    @Test
    fun `the BPM always shows one decimal`() {
        assertThat(DjTempoText.bpm(128.0, Locale.US)).isEqualTo("128.0")
        assertThat(DjTempoText.bpm(129.6, Locale.US)).isEqualTo("129.6")
        assertThat(DjTempoText.bpm(128.0, es)).isEqualTo("128,0")
        assertThat(DjTempoText.bpm(1_024.5, Locale.US)).isEqualTo("1024.5")
    }

    @Test
    fun `an unknown BPM shows a dash`() {
        assertThat(DjTempoText.bpm(null, Locale.US)).isEqualTo(DjTempoText.NO_VALUE)
        assertThat(DjTempoText.bpm(Double.NaN, Locale.US)).isEqualTo(DjTempoText.NO_VALUE)
        assertThat(DjTempoText.bpm(0.0, Locale.US)).isEqualTo(DjTempoText.NO_VALUE)
    }

    @Test
    fun `the tempo is signed with two decimals and a true minus sign`() {
        assertThat(DjTempoText.tempoPercent(1.25, Locale.US)).isEqualTo("+1.25%")
        assertThat(DjTempoText.tempoPercent(-3.0, Locale.US)).isEqualTo("−3.00%")
        assertThat(DjTempoText.tempoPercent(100.0, Locale.US)).isEqualTo("+100.00%")
    }

    @Test
    fun `0 percent is signed like any other tempo`() {
        assertThat(DjTempoText.tempoPercent(0.0, Locale.US)).isEqualTo("+0.00%")
        assertThat(DjTempoText.tempoPercent(-0.0, Locale.US)).isEqualTo("+0.00%")
        assertThat(DjTempoText.tempoPercent(Double.NaN, Locale.US)).isEqualTo("+0.00%")
    }

    @Test
    fun `the tempo follows the locale's percent style with the sign next to the number`() {
        assertThat(DjTempoText.tempoPercent(1.25, es)).isEqualTo("+1,25 %")
        assertThat(DjTempoText.tempoPercent(-0.5, es)).isEqualTo("−0,50 %")
        assertThat(DjTempoText.tempoPercent(1.25, eu)).isEqualTo("% +1,25")
        assertThat(DjTempoText.tempoPercent(0.0, eu)).isEqualTo("% +0,00")
        assertThat(DjTempoText.tempoPercent(0.0, es)).isEqualTo("+0,00 %")
    }

    @Test
    fun `the reset label is 0 percent in the locale's style`() {
        assertThat(DjTempoText.zeroPercent(Locale.US)).isEqualTo("0%")
        assertThat(DjTempoText.zeroPercent(es)).isEqualTo("0 %")
    }

    @Test
    fun `the nudge step reads 0 point 1 in the locale`() {
        assertThat(DjTempoText.nudgeStep(Locale.US)).isEqualTo("0.1")
        assertThat(DjTempoText.nudgeStep(es)).isEqualTo("0,1")
    }

    @Test
    fun `the nudge buttons read minus and plus 0 point 1`() {
        assertThat(DjTempoText.nudgeLabel(-1, Locale.US)).isEqualTo("−0.1")
        assertThat(DjTempoText.nudgeLabel(1, Locale.US)).isEqualTo("+0.1")
    }

    @Test
    fun `the symmetric ranges read plus or minus their percent`() {
        assertThat(DjTempoText.rangeLabel(TempoRange.SIX, Locale.US)).isEqualTo("±6")
        assertThat(DjTempoText.rangeLabel(TempoRange.TEN, Locale.US)).isEqualTo("±10")
        assertThat(DjTempoText.rangeLabel(TempoRange.SIXTEEN, Locale.US)).isEqualTo("±16")
        assertThat(DjTempoText.rangeLabel(TempoRange.WIDE, Locale.US)).isNull()
    }
}
