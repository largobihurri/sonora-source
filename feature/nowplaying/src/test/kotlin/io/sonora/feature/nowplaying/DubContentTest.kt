package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import io.sonora.feature.dub.controls.DubIndicators
import androidx.compose.ui.unit.dp
import io.sonora.feature.dj.controls.DjControlSizes
import org.junit.Test

/** The DUB layout's beat LEDs: beats into the bar from the grid at the position heard. */
class DubContentTest {

    @Test
    fun `the beat LEDs count the bar from the segment playing`() {
        // 120 BPM from 1 000 ms, starting on beat 3 of its bar; 90 BPM from 9 000 ms, on beat 1.
        val grid = DjBeatGrid(listOf(TempoSegment(1_000.0, 120.0, 3), TempoSegment(9_000.0, 90.0, 1)))
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 1_000.0))).isEqualTo(2)
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 1_499.0))).isEqualTo(2)
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 1_500.0))).isEqualTo(3)
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 2_000.0))).isEqualTo(0)
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 9_000.0 + 3 * 666.67))).isEqualTo(3)
        // Before the first segment the first one extends back.
        assertThat(DubIndicators.beatLed(beatsIntoBar(grid, 600.0))).isEqualTo(1)
        assertThat(beatsIntoBar(grid, Double.NaN)).isNull()
    }

    @Test
    fun `the deck controls take one row where eight pads fit and the DJ pad panel's two rows otherwise`() {
        // A 360 dp phone in portrait (16 dp gutters), then in landscape.
        assertThat(dubDeckControls(328.dp)).isEqualTo(DubDeckControls.PAD_PANEL)
        assertThat(dubDeckControls(768.dp)).isEqualTo(DubDeckControls.ONE_ROW)
        // Compact either way: one touch row, or two rows that stay under the DJ layout's compact panel.
        assertThat(DubDeckControls.ONE_ROW.height).isEqualTo(DubDeckSizes.control)
        assertThat(DubDeckControls.PAD_PANEL.height).isEqualTo(DubDeckSizes.modePanel)
        assertThat(DubDeckControls.PAD_PANEL.height).isAtMost(DjControlSizes.Compact.modePanel)
    }
}
