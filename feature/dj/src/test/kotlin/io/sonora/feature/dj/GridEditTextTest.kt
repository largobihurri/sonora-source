package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class GridEditTextTest {

    private val english = Locale.US
    private val spanish = Locale.forLanguageTag("es-ES")
    private val minus = "−"

    @Test
    fun `the tempo has two decimals in the locale's style`() {
        assertThat(GridEditText.bpm(128.0, english)).isEqualTo("128.00")
        assertThat(GridEditText.bpm(128.01, english)).isEqualTo("128.01")
        assertThat(GridEditText.bpm(128.0, spanish)).isEqualTo("128,00")
        assertThat(GridEditText.bpm(87.5, english)).isEqualTo("87.50")
    }

    @Test
    fun `an unknown tempo is a dash`() {
        assertThat(GridEditText.bpm(null, english)).isEqualTo(GridEditText.NO_VALUE)
    }

    @Test
    fun `a tempo step label is signed and has only the decimals it needs`() {
        assertThat(GridEditText.bpmStepLabel(1, english)).isEqualTo("+0.01")
        assertThat(GridEditText.bpmStepLabel(-1, english)).isEqualTo("${minus}0.01")
        assertThat(GridEditText.bpmStepLabel(100, english)).isEqualTo("+1")
        assertThat(GridEditText.bpmStepLabel(-100, english)).isEqualTo("${minus}1")
        assertThat(GridEditText.bpmStepLabel(1, spanish)).isEqualTo("+0,01")
    }

    @Test
    fun `a spoken tempo step has no sign`() {
        assertThat(GridEditText.bpmStep(-1, english)).isEqualTo("0.01")
        assertThat(GridEditText.bpmStep(100, spanish)).isEqualTo("1")
    }

    @Test
    fun `a nudge label is signed with its unit`() {
        assertThat(GridEditText.nudgeLabel(10, "ms", english)).isEqualTo("+10 ms")
        assertThat(GridEditText.nudgeLabel(-1, "ms", english)).isEqualTo("${minus}1 ms")
    }

    @Test
    fun `a spoken nudge step has no sign or unit`() {
        assertThat(GridEditText.nudgeStep(-10, english)).isEqualTo("10")
        assertThat(GridEditText.nudgeStep(1, spanish)).isEqualTo("1")
    }
}
