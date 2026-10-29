package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/*
 * The DJ portrait height budget (D4.1b). The realme GT 7 Pro runs at density 540: 1080x2376 px is 320x704 dp, of which
 * the status bar takes about 35 dp, so the arrangement is about 669 dp tall (measured on a device screenshot, see the
 * D4.1b entry of odd/tasks/dj-mode.md). A 412x905 dp phone leaves about 870 dp.
 */
class DjPortraitBudgetTest {

    private val realme = 669.dp
    private val tall = 870.dp

    @Test
    fun `on the realme the scrolling waveform keeps its minimum height`() {
        val metrics = djPortraitMetrics(realme, editorOpen = false)
        assertThat(metrics.waveformHeight(realme, editorOpen = false).value).isAtLeast(DjWaveformMinHeight.value)
    }

    @Test
    fun `on the realme the compact density keeps the volume slider`() {
        val metrics = djPortraitMetrics(realme, editorOpen = false)
        assertThat(metrics.density).isEqualTo(DjDensity.COMPACT)
        assertThat(metrics.showsVolume).isTrue()
    }

    @Test
    fun `on a tall phone the comfortable density is used and the waveform grows past its minimum`() {
        val metrics = djPortraitMetrics(tall, editorOpen = false)
        assertThat(metrics.density).isEqualTo(DjDensity.COMFORTABLE)
        assertThat(metrics.waveformHeight(tall, editorOpen = false).value).isAtLeast(DjWaveformMinHeight.value)
    }

    @Test
    fun `with the grid editor open the waveform keeps its minimum height on the realme`() {
        val metrics = djPortraitMetrics(realme, editorOpen = true)
        assertThat(metrics.waveformHeight(realme, editorOpen = true).value).isAtLeast(DjWaveformMinHeight.value)
    }

    @Test
    fun `with the grid editor open the waveform keeps its minimum height on a tall phone`() {
        val metrics = djPortraitMetrics(tall, editorOpen = true)
        assertThat(metrics.waveformHeight(tall, editorOpen = true).value).isAtLeast(DjWaveformMinHeight.value)
    }

    @Test
    fun `the editor takes the place of the cue panel, the tempo section, the volume row and the bottom capsule`() {
        val metrics = djPortraitMetrics(realme, editorOpen = false)
        assertThat(metrics.fixedHeight(editorOpen = true)).isLessThan(metrics.fixedHeight(editorOpen = false))
    }

    @Test
    fun `a phone too short for the compact density drops the volume row`() {
        val metrics = djPortraitMetrics(630.dp, editorOpen = false)
        assertThat(metrics.density).isEqualTo(DjDensity.TIGHT)
        assertThat(metrics.showsVolume).isFalse()
    }

    @Test
    fun `no height is ever laid out denser than the tight density`() {
        val metrics = djPortraitMetrics(400.dp, editorOpen = false)
        assertThat(metrics.density).isEqualTo(DjDensity.TIGHT)
    }

    @Test
    fun `a denser density is chosen only as the room shrinks`() {
        val densities = (400..1000 step 10).map { djPortraitMetrics(it.dp, editorOpen = false).density.ordinal }
        assertThat(densities).isInOrder(Comparator.reverseOrder<Int>())
    }

    @Test
    fun `every density is shorter than the one before it`() {
        val comfortable = djPortraitMetrics(tall, editorOpen = false).fixedHeight(editorOpen = false)
        val compact = djPortraitMetrics(realme, editorOpen = false).fixedHeight(editorOpen = false)
        val tight = djPortraitMetrics(630.dp, editorOpen = false).fixedHeight(editorOpen = false)
        assertThat(compact).isLessThan(comfortable)
        assertThat(tight).isLessThan(compact)
    }

    @Test
    fun `the compact budget adds up row by row`() {
        // grabber 15 + header (4 + 44 + 6) + timeline (6 + 40 + 2 + 20) + controls (4 + cue 78 + 4 + tempo 122 + 4 +
        // transport 56 + 4 + volume 32 + 4 + capsule 44) + bottom 8.
        val metrics = djPortraitMetrics(realme, editorOpen = false)
        assertThat(metrics.fixedHeight(editorOpen = false)).isEqualTo(497.dp)
        assertThat(metrics.waveformHeight(realme, editorOpen = false)).isEqualTo(172.dp)
    }

    @Test
    fun `the compact editor budget adds up row by row`() {
        // grabber 15 + header 54 + timeline 68 + controls (4 + editor 260 + 4 + transport 56) + bottom 8.
        val metrics = djPortraitMetrics(realme, editorOpen = true)
        assertThat(metrics.fixedHeight(editorOpen = true)).isEqualTo(469.dp)
    }

    @Test
    fun `the waveform height never goes below zero`() {
        val metrics = djPortraitMetrics(100.dp, editorOpen = false)
        assertThat(metrics.waveformHeight(100.dp, editorOpen = false).value).isAtLeast(0f)
    }

    @Test
    fun `the loop controls swap with the cue panel inside the same height, so the budget is the one of D4-1b`() {
        // The Cues | Loop switch replaces the pad row and the Q row with the loop transport row and the stepper row:
        // 40 + 4 + 34 = 78 dp either way, so the realme still has 172 dp of waveform and the editor's 200 dp.
        val metrics = djPortraitMetrics(realme, editorOpen = false)
        assertThat(metrics.controls.loopPanel).isEqualTo(metrics.controls.cuePanel)
        assertThat(metrics.controls.modePanel).isEqualTo(78.dp)
        assertThat(metrics.fixedHeight(editorOpen = false)).isEqualTo(497.dp)
        assertThat(metrics.waveformHeight(realme, editorOpen = false)).isEqualTo(172.dp)
        assertThat(djPortraitMetrics(realme, editorOpen = true).waveformHeight(realme, editorOpen = true)).isEqualTo(200.dp)
    }

    @Test
    fun `the waveform keeps its minimum in both pad modes at every density`() {
        for (available in listOf(630.dp, 657.dp, realme, 760.dp, tall)) {
            val metrics = djPortraitMetrics(available, editorOpen = false)
            val mode = metrics.controls.modePanel
            // The budget reserves the taller panel, so neither mode can push the waveform below what was promised.
            assertThat(mode).isAtLeast(metrics.controls.cuePanel)
            assertThat(mode).isAtLeast(metrics.controls.loopPanel)
        }
        assertThat(djPortraitMetrics(realme, editorOpen = false).waveformHeight(realme, editorOpen = false).value)
            .isAtLeast(DjWaveformMinHeight.value)
    }

    // --- tablets (T6): the Pad 6 is about 720x1152 dp upright, of which the status bar takes about 24 dp ---

    private val pad6 = 1128.dp
    private val tablet1280 = 1256.dp

    private fun tablet(available: Dp, editorOpen: Boolean = false) = djPortraitMetrics(available, editorOpen, large = true)

    @Test
    fun `on the Pad 6 upright the tablet density is used and the waveform is far taller than a phone's`() {
        val metrics = tablet(pad6)

        assertThat(metrics.density).isEqualTo(DjDensity.TABLET)
        assertThat(metrics.waveformHeight(pad6, editorOpen = false).value).isAtLeast(380f)
        assertThat(metrics.waveformHeight(pad6, editorOpen = false))
            .isGreaterThan(djPortraitMetrics(tall, editorOpen = false).waveformHeight(tall, editorOpen = false))
    }

    @Test
    fun `on an 800x1280 tablet upright the tablet density gives the waveform the extra height`() {
        val metrics = tablet(tablet1280)

        assertThat(metrics.density).isEqualTo(DjDensity.TABLET)
        assertThat(metrics.waveformHeight(tablet1280, editorOpen = false).value)
            .isAtLeast(metrics.waveformHeight(pad6, editorOpen = false).value + 100f)
    }

    @Test
    fun `the tablet budget adds up row by row`() {
        // grabber 21 + header (8 + 64 + 16) + timeline (8 + 56 + 4 + 24) + top gap 12 + controls (cue 112 + 8 + tempo 176 + 8 +
        // transport 88 + volume row (8 + 32) + 8 + capsule 56) + bottom 20.
        val metrics = tablet(pad6)
        assertThat(metrics.fixedHeight(editorOpen = false)).isEqualTo(729.dp)
        assertThat(metrics.waveformHeight(pad6, editorOpen = false)).isEqualTo(399.dp)
        assertThat(metrics.waveformHeight(tablet1280, editorOpen = false)).isEqualTo(527.dp)
    }

    @Test
    fun `the tablet editor budget adds up and keeps the waveform tall`() {
        // grabber 21 + header 88 + timeline 92 + top gap 12 + (editor 376 + 8 + transport 88) + bottom 20.
        val metrics = tablet(pad6, editorOpen = true)
        assertThat(metrics.fixedHeight(editorOpen = true)).isEqualTo(705.dp)
        assertThat(metrics.waveformHeight(pad6, editorOpen = true).value).isAtLeast(DjWaveformMinHeight.value)
        assertThat(metrics.waveformHeight(pad6, editorOpen = true)).isEqualTo(423.dp)
    }

    @Test
    fun `the tablet has no 560 dp cap, the phones keep theirs`() {
        assertThat(tablet(pad6).contentMaxWidth.isSpecified).isFalse()
        for (available in listOf(630.dp, realme, tall)) {
            assertThat(djPortraitMetrics(available, editorOpen = false).contentMaxWidth).isEqualTo(560.dp)
        }
    }

    @Test
    fun `tablet controls are comfortable for fingers`() {
        val metrics = tablet(pad6)

        assertThat(metrics.controls.control.value).isAtLeast(52f)
        assertThat(metrics.controls.slim.value).isAtLeast(44f)
        assertThat(metrics.transportPlay.value).isAtLeast(72f)
        // A glyph button's touch target is 16 dp larger than its glyph.
        assertThat(metrics.transportGlyph.value + 16f).isAtLeast(52f)
        assertThat(metrics.bottomButtons.value).isAtLeast(52f)
        assertThat(metrics.showsVolume).isTrue()
    }

    @Test
    fun `the transport and the capsule keep a sensible width on a wide window`() {
        val metrics = tablet(pad6)
        assertThat(metrics.transportMaxWidth.value).isAtMost(600f)
        assertThat(metrics.capsuleMaxWidth.value).isAtMost(520f)
        assertThat(metrics.capsuleMaxWidth.value).isAtLeast(metrics.bottomButtons.value * 5)
    }

    @Test
    fun `a window that is not large never gets the tablet density, whatever its height`() {
        for (available in listOf(630.dp, realme, tall, pad6, tablet1280)) {
            assertThat(djPortraitMetrics(available, editorOpen = false).density).isNotEqualTo(DjDensity.TABLET)
            assertThat(djPortraitMetrics(available, editorOpen = false, large = false).density)
                .isEqualTo(djPortraitMetrics(available, editorOpen = false).density)
        }
    }

    @Test
    fun `a large window too short for the tablet density falls back to the phone ladder`() {
        val short = 760.dp
        assertThat(tablet(short).density).isNotEqualTo(DjDensity.TABLET)
        assertThat(tablet(short)).isEqualTo(djPortraitMetrics(short, editorOpen = false))
        assertThat(tablet(short).waveformHeight(short, editorOpen = false).value).isAtLeast(DjWaveformMinHeight.value)
    }

    @Test
    fun `the tablet density starts when it still leaves the waveform its minimum`() {
        val atLimit = 729.dp + DjWaveformMinHeight
        assertThat(tablet(atLimit).density).isEqualTo(DjDensity.TABLET)
        assertThat(tablet(atLimit - 1.dp).density).isNotEqualTo(DjDensity.TABLET)
    }

    @Test
    fun `on a large window a taller window never picks a denser density`() {
        val densities = (400..1400 step 10).map { tablet(it.dp).density.ordinal }
        assertThat(densities).isInOrder(Comparator.reverseOrder<Int>())
    }
}
