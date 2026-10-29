package io.sonora.feature.dj.controls

import androidx.compose.ui.unit.dp
import io.sonora.feature.dj.DjTimelineSizes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DjControlSizesTest {

    @Test
    fun `the cue panel is the pad row, a gap and the slim memory row`() {
        assertThat(DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp).cuePanel).isEqualTo(78.dp)
    }

    @Test
    fun `the tempo section is the readout, the fader and the slim range row with two gaps`() {
        assertThat(DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp).tempoSection).isEqualTo(122.dp)
    }

    @Test
    fun `the grid editor is six control rows with five gaps`() {
        assertThat(DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp).gridEditor).isEqualTo(260.dp)
    }

    @Test
    fun `the comfortable sizes are the 44 dp rows the screen had before the cue panel`() {
        val sizes = DjControlSizes.Comfortable
        assertThat(sizes.control).isEqualTo(44.dp)
        assertThat(sizes.slim).isEqualTo(44.dp)
        assertThat(sizes.gridEditor).isEqualTo(294.dp)
    }

    @Test
    fun `the compact sizes are smaller than the comfortable ones in every row`() {
        val compact = DjControlSizes.Compact
        val comfortable = DjControlSizes.Comfortable
        assertThat(compact.control).isLessThan(comfortable.control)
        assertThat(compact.slim).isLessThan(comfortable.slim)
        assertThat(compact.gap).isLessThan(comfortable.gap)
        assertThat(compact.cuePanel).isLessThan(comfortable.cuePanel)
        assertThat(compact.tempoSection).isLessThan(comfortable.tempoSection)
        assertThat(compact.gridEditor).isLessThan(comfortable.gridEditor)
    }

    @Test
    fun `the compact rows still hold the touch targets the layout promises`() {
        // Pads, nudges and editor buttons stay at least 40 dp tall; the slim rows hold text pills, never below 32 dp.
        assertThat(DjControlSizes.Compact.control.value).isAtLeast(40f)
        assertThat(DjControlSizes.Compact.slim.value).isAtLeast(32f)
    }

    @Test
    fun `the loop panel is as tall as the cue panel, so swapping the pad mode never changes the screen's height`() {
        for (sizes in listOf(DjControlSizes.Comfortable, DjControlSizes.Compact, DjControlSizes(40.dp, 34.dp, 4.dp))) {
            assertThat(sizes.loopPanel).isEqualTo(sizes.cuePanel)
            assertThat(sizes.modePanel).isEqualTo(sizes.cuePanel)
        }
    }

    @Test
    fun `the mode panel is the taller of the two panels`() {
        // Only the definition: a future loop panel that grows is what the budget has to reserve.
        val sizes = DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp)
        assertThat(sizes.modePanel).isEqualTo(maxOf(sizes.cuePanel, sizes.loopPanel))
    }

    @Test
    fun `on the narrowest phone every loop button keeps a usable width`() {
        // 320 dp wide, 16 dp margins: 288 dp of content, 4 dp gaps.
        val content = 288.dp
        val gap = DjControlSizes.Compact.gap
        assertThat(DjPanelLayout.transportWidths(content, gap).minOf { it.value }).isAtLeast(48f)
        assertThat(DjPanelLayout.transportWidths(content, gap)[2].value).isAtLeast(68f)
        assertThat(DjPanelLayout.cueRowWidths(content, gap).minOf { it.value }).isAtLeast(32f)
        assertThat(DjPanelLayout.cueRowWidths(content, gap)[1].value).isAtLeast(76f)
        assertThat(DjPanelLayout.stepperValueWidth(content, gap).value).isAtLeast(80f)
    }

    @Test
    fun `the widths of a row add up to the content width`() {
        val content = 288.dp
        val gap = 4.dp
        val transport = DjPanelLayout.transportWidths(content, gap)
        assertThat(transport.sumOf { it.value.toDouble() } + gap.value * (transport.size - 1)).isWithin(0.01).of(288.0)
        val cues = DjPanelLayout.cueRowWidths(content, gap)
        val fixed = DjPanelLayout.ModeSwitchWidth.value + DjPanelLayout.QuantizeWidth.value
        assertThat(fixed + cues.sumOf { it.value.toDouble() } + gap.value * 4).isWithin(0.01).of(288.0)
    }

    @Test
    fun `the tablet sizes are comfortable for fingers, 52 dp or more on the touch rows`() {
        val tablet = DjControlSizes.Tablet
        assertThat(tablet.control.value).isAtLeast(52f)
        assertThat(tablet.slim.value).isAtLeast(44f)
        assertThat(tablet.control).isAtLeast(DjControlSizes.Comfortable.control)
        assertThat(tablet.slim).isAtLeast(DjControlSizes.Comfortable.slim)
        assertThat(tablet.gap).isAtLeast(DjControlSizes.Comfortable.gap)
    }

    @Test
    fun `the tablet loop and cue panels are as tall as each other too`() {
        assertThat(DjControlSizes.Tablet.loopPanel).isEqualTo(DjControlSizes.Tablet.cuePanel)
        assertThat(DjControlSizes.Tablet.modePanel).isEqualTo(112.dp)
    }

    @Test
    fun `the tempo column stacks the readout, the nudge row, the range and Master Tempo with three gaps`() {
        assertThat(DjControlSizes.Tablet.tempoColumn).isEqualTo(56.dp + 56.dp + 48.dp + 48.dp + 8.dp * 3)
        assertThat(DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp).tempoColumn).isEqualTo(40.dp + 40.dp + 34.dp + 34.dp + 12.dp)
    }

    @Test
    fun `the tablet timeline has a taller overview and time row than the comfortable one`() {
        val tablet = DjTimelineSizes.Tablet
        assertThat(tablet.overviewHeight).isGreaterThan(DjTimelineSizes.Comfortable.overviewHeight)
        assertThat(tablet.timeRow).isGreaterThan(DjTimelineSizes.Comfortable.timeRow)
        assertThat(tablet.fixedHeight).isEqualTo(92.dp)
    }

    @Test
    fun `the one-row deck strip is offered only where its eight pads keep a usable width`() {
        val gap = DjControlSizes.Compact.gap
        // A 360 dp phone in portrait (16 dp margins): eight pads after the switch would be about 27 dp wide.
        assertThat(DjPanelLayout.deckRowPadWidth(328.dp, gap).value).isWithin(0.01f).of(27f)
        assertThat(DjPanelLayout.deckRowFits(328.dp, gap)).isFalse()
        // The same phone in landscape, and a tablet.
        assertThat(DjPanelLayout.deckRowFits(768.dp, gap)).isTrue()
        assertThat(DjPanelLayout.deckRowFits(1_000.dp, gap)).isTrue()
        // The threshold is exactly the minimum pad width.
        val fits = DjPanelLayout.ModeSwitchWidth + gap * 8 + DjPanelLayout.DeckPadMinWidth * 8
        assertThat(DjPanelLayout.deckRowFits(fits, gap)).isTrue()
        assertThat(DjPanelLayout.deckRowFits(fits - 1.dp, gap)).isFalse()
    }
}
