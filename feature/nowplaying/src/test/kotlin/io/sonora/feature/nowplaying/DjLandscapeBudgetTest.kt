package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/*
 * The DJ landscape height and width budget (D2.10). The realme GT 7 Pro runs at density 540: 1080x2376 px is 704x320 dp,
 * so a landscape arrangement is at most 320 dp tall, less whatever the system bars take (the arrangement is given what
 * is left after the window's top bar has been cleared). A tablet in landscape is about 800 dp tall.
 */
class DjLandscapeBudgetTest {

    private val realme = 320.dp

    /** The realme with a top bar that has to be cleared (the layout shifts down by it) and a bottom inset: about this tall. */
    private val realmeWithBars = 288.dp
    private val tablet = 800.dp

    private fun waveform(available: Dp, editorOpen: Boolean = false) =
        djLandscapeMetrics(available, editorOpen).waveformHeight(available, editorOpen)

    @Test
    fun `on the realme the scrolling waveform keeps its minimum height`() {
        assertThat(waveform(realme).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
    }

    @Test
    fun `the minimum is about 120 dp`() {
        assertThat(DjLandscapeWaveformMinHeight).isEqualTo(120.dp)
    }

    @Test
    fun `on the realme the compact density is used, without the bottom row`() {
        val metrics = djLandscapeMetrics(realme, editorOpen = false)

        assertThat(metrics.density).isEqualTo(DjLandscapeDensity.COMPACT)
        assertThat(metrics.showsBottomRow).isFalse()
    }

    @Test
    fun `on the realme the waveform is 141 dp in the 320 dp window`() {
        // 15 grabber + 38 header + 4 + 116 deck + 6 bottom padding = 179 fixed.
        assertThat(djLandscapeMetrics(realme, editorOpen = false).fixedHeight(editorOpen = false)).isEqualTo(179.dp)
        assertThat(waveform(realme)).isEqualTo(141.dp)
    }

    @Test
    fun `a window about 290 dp tall is packed tighter instead of shrinking the waveform below its minimum`() {
        val metrics = djLandscapeMetrics(realmeWithBars, editorOpen = false)

        assertThat(metrics.density).isEqualTo(DjLandscapeDensity.TIGHT)
        assertThat(waveform(realmeWithBars).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
    }

    @Test
    fun `on a tablet the comfortable density is used and brings the volume and capsule row`() {
        val metrics = djLandscapeMetrics(tablet, editorOpen = false)

        assertThat(metrics.density).isEqualTo(DjLandscapeDensity.COMFORTABLE)
        assertThat(metrics.showsBottomRow).isTrue()
        assertThat(waveform(tablet).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
    }

    @Test
    fun `the comfortable density starts when it still leaves the waveform its minimum`() {
        val atLimit = 315.dp + DjLandscapeWaveformMinHeight
        assertThat(djLandscapeMetrics(atLimit, editorOpen = false).density).isEqualTo(DjLandscapeDensity.COMFORTABLE)
        assertThat(djLandscapeMetrics(atLimit - 1.dp, editorOpen = false).density).isEqualTo(DjLandscapeDensity.COMPACT)
    }

    @Test
    fun `a window too short even for the tight density keeps the tight rows and a smaller waveform`() {
        val metrics = djLandscapeMetrics(240.dp, editorOpen = false)

        assertThat(metrics.density).isEqualTo(DjLandscapeDensity.TIGHT)
        assertThat(metrics.waveformHeight(240.dp, editorOpen = false).value).isAtLeast(0f)
        assertThat(metrics.waveformHeight(100.dp, editorOpen = false)).isEqualTo(0.dp)
    }

    @Test
    fun `with the grid editor open the waveform keeps its minimum on the realme and on a tablet`() {
        for (available in listOf(realme, realmeWithBars, tablet)) {
            assertThat(waveform(available, editorOpen = true).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
        }
    }

    @Test
    fun `the grid editor fits beside the waveform on the realme and on a tablet`() {
        for (available in listOf(realme, realmeWithBars, tablet)) {
            val metrics = djLandscapeMetrics(available, editorOpen = true)
            assertThat(metrics.editorFits(available)).isTrue()
        }
    }

    @Test
    fun `the editor takes the place of the pad panel and the tempo section, so the waveform grows`() {
        val metrics = djLandscapeMetrics(realme, editorOpen = false)

        assertThat(metrics.fixedHeight(editorOpen = true)).isLessThan(metrics.fixedHeight(editorOpen = false))
    }

    @Test
    fun `the tempo section fits beside the overview and the pad panel in every density`() {
        for (available in listOf(240.dp, realmeWithBars, realme, tablet)) {
            val metrics = djLandscapeMetrics(available, editorOpen = false)
            assertThat(metrics.deckHeight.value).isAtLeast(metrics.controls.tempoSection.value)
        }
    }

    @Test
    fun `the densest rows are never taller than the roomier ones`() {
        val heights = listOf(tablet, realme, realmeWithBars).map { djLandscapeMetrics(it, editorOpen = false).fixedHeight(editorOpen = false) }

        assertThat(heights.zipWithNext().all { (roomier, denser) -> denser <= roomier }).isTrue()
    }

    @Test
    fun `the header holds the artwork, the transport and two lines of text`() {
        for (available in listOf(realmeWithBars, realme, tablet)) {
            val metrics = djLandscapeMetrics(available, editorOpen = false)
            assertThat(metrics.headerHeight.value).isAtLeast(metrics.transportPlay.value)
            assertThat(metrics.headerHeight.value).isAtLeast(metrics.headerArtwork.value)
        }
    }

    // --- widths ---

    @Test
    fun `on the realme the tempo section keeps 288 dp and the pad panel gets the rest`() {
        val widths = djLandscapeWidths(624.dp)

        assertThat(widths.tempo).isEqualTo(288.dp)
        assertThat(widths.left).isEqualTo(328.dp)
        assertThat(widths.left + widths.gap + widths.tempo).isEqualTo(624.dp)
    }

    @Test
    fun `even beside a wide camera cutout the pad panel keeps 280 dp`() {
        // 704 dp wide, 48 dp of cutout on both sides, 16 dp margins.
        assertThat(djLandscapeWidths(576.dp).left.value).isAtLeast(280f)
    }

    @Test
    fun `on a tablet the tempo section grows to 360 dp and no further`() {
        val widths = djLandscapeWidths(1200.dp)

        assertThat(widths.tempo).isEqualTo(360.dp)
        assertThat(widths.left + widths.gap + widths.tempo).isEqualTo(1200.dp)
    }

    // --- tablets (T6): the Pad 6 is about 1152x720 dp sideways, of which the status bar takes about 24 dp ---

    private val pad6 = 696.dp
    private val pad6Width = 1152.dp
    private val tablet1280 = 776.dp
    private val tablet1280Width = 1280.dp

    private fun tablet(available: Dp, width: Dp, editorOpen: Boolean = false) =
        djLandscapeMetrics(available, editorOpen, width = width, large = true)

    @Test
    fun `on the Pad 6 sideways the tablet density is used, with the vertical tempo column and a roomy waveform`() {
        val metrics = tablet(pad6, pad6Width)

        assertThat(metrics.density).isEqualTo(DjLandscapeDensity.TABLET)
        assertThat(metrics.tabletDeck).isTrue()
        assertThat(metrics.showsBottomRow).isTrue()
        assertThat(metrics.waveformHeight(pad6, editorOpen = false).value).isAtLeast(300f)
    }

    @Test
    fun `the tablet landscape budget adds up row by row at 1152x720 and 1280x800`() {
        // grabber 21 + gap 4 + header 64 + gap 8 + overview gap 8 + deck block (overview 56 + 4 + times 24 + gap 8 + pads 112) +
        // bottom row (8 + 52) + bottom 16.
        val metrics = tablet(pad6, pad6Width)
        assertThat(metrics.fixedHeight(editorOpen = false)).isEqualTo(385.dp)
        assertThat(metrics.waveformHeight(pad6, editorOpen = false)).isEqualTo(311.dp)
        assertThat(tablet(tablet1280, tablet1280Width).waveformHeight(tablet1280, editorOpen = false)).isEqualTo(391.dp)
    }

    @Test
    fun `the tempo column starts at the overview and runs down to the bottom row, a long fader`() {
        val metrics = tablet(pad6, pad6Width)

        // overview 56 + 4 + times 24 + gap 8 + pads 112 + bottom row (8 + 52).
        assertThat(metrics.tempoColumnHeight).isEqualTo(264.dp)
        assertThat(metrics.tempoColumnHeight).isGreaterThan(metrics.deckHeight + 50.dp)
    }

    @Test
    fun `the tempo column holds its controls beside the fader in every tablet window`() {
        for ((height, width) in listOf(pad6 to pad6Width, tablet1280 to tablet1280Width, 570.dp to 1024.dp)) {
            val metrics = tablet(height, width)
            assertThat(metrics.density).isEqualTo(DjLandscapeDensity.TABLET)
            assertThat(metrics.tempoColumnHeight.value).isAtLeast(metrics.controls.tempoColumn.value)
        }
    }

    @Test
    fun `the tablet pad panel gets pads at least 44 dp wide in the tablet windows`() {
        for (width in listOf(pad6Width, tablet1280Width, 1024.dp)) {
            val metrics = tablet(pad6, width)
            val widths = djLandscapeWidths(width - metrics.sidePadding * 2, tablet = true)
            val panel = minOf(metrics.padPanelWidth(widths.left), DjTabletMaxPadPanel)
            val pad = (panel - metrics.controls.gap * 7) / 8
            assertThat(pad.value).isAtLeast(44f)
        }
    }

    @Test
    fun `a large window too narrow for the pads beside the transport falls back to the phone ladder`() {
        val narrow = tablet(pad6, 800.dp)
        assertThat(narrow.tabletDeck).isFalse()
        assertThat(narrow).isEqualTo(djLandscapeMetrics(pad6, editorOpen = false))
        assertThat(tablet(pad6, pad6Width).fitsWidth(800.dp)).isFalse()
        assertThat(tablet(pad6, pad6Width).fitsWidth(pad6Width)).isTrue()
    }

    @Test
    fun `a large window too short for the tablet density falls back to the phone ladder`() {
        val metrics = tablet(400.dp, pad6Width)
        assertThat(metrics.tabletDeck).isFalse()
        assertThat(metrics.waveformHeight(400.dp, editorOpen = false).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
    }

    @Test
    fun `a window that is not large never gets the tablet density, whatever its size`() {
        for ((height, width) in listOf(realme to 704.dp, pad6 to pad6Width, tablet1280 to tablet1280Width)) {
            val metrics = djLandscapeMetrics(height, editorOpen = false, width = width, large = false)
            assertThat(metrics).isEqualTo(djLandscapeMetrics(height, editorOpen = false))
            assertThat(metrics.tabletDeck).isFalse()
        }
    }

    @Test
    fun `tablet controls are comfortable for fingers and the transport leaves the header`() {
        val metrics = tablet(pad6, pad6Width)

        assertThat(metrics.controls.control.value).isAtLeast(52f)
        assertThat(metrics.controls.slim.value).isAtLeast(44f)
        assertThat(metrics.transportPlay.value).isAtLeast(72f)
        assertThat(metrics.transportGlyph.value + 16f).isAtLeast(52f)
        assertThat(metrics.bottomButtons.value).isAtLeast(52f)
        assertThat(metrics.showsHeaderTransport).isFalse()
        assertThat(metrics.transportPlay.value).isAtMost(metrics.controls.modePanel.value)
        assertThat(djLandscapeMetrics(realme, editorOpen = false).showsHeaderTransport).isTrue()
    }

    @Test
    fun `the transport block holds previous, play and next in its width`() {
        val metrics = tablet(pad6, pad6Width)
        val needed = (metrics.transportGlyph + 16.dp) * 2 + metrics.transportPlay
        assertThat(metrics.deckTransportWidth.value).isAtLeast(needed.value)
    }

    @Test
    fun `the tablet bottom capsule is wide enough for five buttons`() {
        val metrics = tablet(pad6, pad6Width)
        assertThat(metrics.bottomCapsuleWidth.value).isAtLeast(metrics.bottomButtons.value * 5)
        assertThat(djLandscapeMetrics(tablet1280, editorOpen = false).bottomCapsuleWidth).isEqualTo(300.dp)
    }

    @Test
    fun `the tablet editor keeps the waveform tall and fits in the right column`() {
        for ((height, width) in listOf(pad6 to pad6Width, tablet1280 to tablet1280Width)) {
            val metrics = tablet(height, width, editorOpen = true)
            assertThat(metrics.tabletDeck).isTrue()
            assertThat(metrics.waveformHeight(height, editorOpen = true).value).isAtLeast(DjLandscapeWaveformMinHeight.value)
            assertThat(metrics.editorFits(height)).isTrue()
        }
        // header 97 + overview block 92 + bottom 16 = 205: the transport stands in the right column, under the editor.
        assertThat(tablet(pad6, pad6Width, editorOpen = true).fixedHeight(editorOpen = true)).isEqualTo(205.dp)
    }

    @Test
    fun `with the editor open the tablet waveform takes the height the transport used to need`() {
        // 696 - 205 = 491 on the Pad 6 (411 while the transport stood under the waveform), 776 - 205 = 571 at 1280x800.
        assertThat(tablet(pad6, pad6Width, editorOpen = true).waveformHeight(pad6, editorOpen = true)).isEqualTo(491.dp)
        assertThat(tablet(tablet1280, tablet1280Width, editorOpen = true).waveformHeight(tablet1280, editorOpen = true)).isEqualTo(571.dp)
    }

    @Test
    fun `the tablet editor column holds the six editor rows and the transport under them`() {
        val metrics = tablet(pad6, pad6Width, editorOpen = true)

        // Six 56 dp rows with 8 dp gaps (376), the gap, and the 80 dp transport.
        assertThat(metrics.editorColumnHeight).isEqualTo(464.dp)
        assertThat(metrics.editorFits(pad6)).isTrue()
        // A phone's editor column is the editor alone: its transport stays in the header.
        val phone = djLandscapeMetrics(realme, editorOpen = true)
        assertThat(phone.editorColumnHeight).isEqualTo(phone.controls.gridEditor)
    }

    @Test
    fun `a tablet window too short for the editor and the transport together takes the phone ladder while editing`() {
        // Enough for the editor (376) alone, not for the editor and the transport (464): 97 + 16 + 464 = 577.
        val short = 570.dp
        assertThat(tablet(short, pad6Width, editorOpen = false).tabletDeck).isTrue()
        assertThat(tablet(short, pad6Width, editorOpen = true).tabletDeck).isFalse()
        assertThat(tablet(577.dp, pad6Width, editorOpen = true).tabletDeck).isTrue()
    }

    @Test
    fun `the tablet tempo column is wider than a phone's and grows with the window`() {
        val pad6Widths = djLandscapeWidths(pad6Width - 64.dp, tablet = true)
        val wide = djLandscapeWidths(tablet1280Width - 64.dp, tablet = true)

        assertThat(pad6Widths.tempo.value).isAtLeast(288f)
        assertThat(wide.tempo).isGreaterThan(pad6Widths.tempo)
        assertThat(wide.tempo.value).isAtMost(340f)
        assertThat(pad6Widths.left + pad6Widths.gap + pad6Widths.tempo).isEqualTo(pad6Width - 64.dp)
        // A phone keeps its widths.
        assertThat(djLandscapeWidths(624.dp)).isEqualTo(djLandscapeWidths(624.dp, tablet = false))
        assertThat(djLandscapeWidths(624.dp).tempo).isEqualTo(288.dp)
    }

    @Test
    fun `on a large window a taller window never picks a denser landscape density`() {
        val densities = (250..900 step 10).map { tablet(it.dp, pad6Width).density.ordinal }
        assertThat(densities).isInOrder(Comparator.reverseOrder<Int>())
    }
}
