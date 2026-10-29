package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.layout.WindowClass
import org.junit.Test

class NowPlayingLargeLayoutTest {

    /** Float arithmetic on dp is exact to about this much. */
    private val Tolerance = 0.01f

    private val pad6Landscape = WindowClass.of(1152, 720)
    private val pad6Portrait = WindowClass.of(720, 1152)

    // ---- Which arrangement a window gets

    @Test
    fun `a phone keeps the phone arrangements in both orientations`() {
        assertThat(nowPlayingStyle(WindowClass.of(411, 891))).isEqualTo(NowPlayingStyle.Phone)
        assertThat(nowPlayingStyle(WindowClass.of(891, 411))).isEqualTo(NowPlayingStyle.Phone)
    }

    @Test
    fun `a tablet gets the large arrangements in both orientations`() {
        assertThat(nowPlayingStyle(pad6Landscape)).isEqualTo(NowPlayingStyle.Large)
        assertThat(nowPlayingStyle(pad6Portrait)).isEqualTo(NowPlayingStyle.Large)
        assertThat(nowPlayingStyle(WindowClass.of(600, 960))).isEqualTo(NowPlayingStyle.Large)
    }

    @Test
    fun `a split window under 600 dp on its short side keeps the phone arrangements`() {
        assertThat(nowPlayingStyle(WindowClass.of(580, 1152))).isEqualTo(NowPlayingStyle.Phone)
    }

    @Test
    fun `DJ mode keeps the phone style even on a tablet, its layouts are their own`() {
        assertThat(nowPlayingStyle(pad6Landscape, djMode = true)).isEqualTo(NowPlayingStyle.Phone)
    }

    // ---- Which panel takes the artwork's place

    @Test
    fun `on a phone only lyrics replace the artwork, the queue is a sheet`() {
        assertThat(sidePanelShown(NowPlayingStyle.Phone, PanelState(lyrics = true))).isEqualTo(SidePanel.Lyrics)
        assertThat(sidePanelShown(NowPlayingStyle.Phone, PanelState(queue = true))).isEqualTo(SidePanel.None)
        assertThat(queueAsSheet(NowPlayingStyle.Phone, PanelState(queue = true))).isTrue()
    }

    @Test
    fun `on a tablet lyrics and the queue both replace the artwork and the queue is no sheet`() {
        assertThat(sidePanelShown(NowPlayingStyle.Large, PanelState())).isEqualTo(SidePanel.None)
        assertThat(sidePanelShown(NowPlayingStyle.Large, PanelState(lyrics = true))).isEqualTo(SidePanel.Lyrics)
        assertThat(sidePanelShown(NowPlayingStyle.Large, PanelState(queue = true))).isEqualTo(SidePanel.Queue)
        assertThat(queueAsSheet(NowPlayingStyle.Large, PanelState(queue = true))).isFalse()
    }

    @Test
    fun `on a tablet opening the queue closes the lyrics and the other way round`() {
        val lyricsOpen = PanelState(lyrics = true)
        assertThat(lyricsOpen.queueTapped(NowPlayingStyle.Large)).isEqualTo(PanelState(queue = true))
        val queueOpen = PanelState(queue = true)
        assertThat(queueOpen.lyricsTapped(NowPlayingStyle.Large)).isEqualTo(PanelState(lyrics = true))
    }

    @Test
    fun `on a tablet tapping the open panel's button closes it`() {
        assertThat(PanelState(lyrics = true).lyricsTapped(NowPlayingStyle.Large)).isEqualTo(PanelState())
        assertThat(PanelState(queue = true).queueTapped(NowPlayingStyle.Large)).isEqualTo(PanelState())
    }

    @Test
    fun `on a phone the queue button opens the sheet and leaves the lyrics alone`() {
        assertThat(PanelState(lyrics = true).queueTapped(NowPlayingStyle.Phone)).isEqualTo(PanelState(lyrics = true, queue = true))
        assertThat(PanelState().lyricsTapped(NowPlayingStyle.Phone)).isEqualTo(PanelState(lyrics = true))
        assertThat(PanelState(lyrics = true).lyricsTapped(NowPlayingStyle.Phone)).isEqualTo(PanelState())
    }

    // ---- Landscape: the artwork on the leading side, the controls scaled up on the trailing side

    // The Pad 6 in landscape less the status bar, the handle row and the bottom margin.
    private val pad6LandscapeArea = Pair(1152.dp, 663.dp)

    @Test
    fun `the Pad 6 landscape controls are scaled up, well past phone size`() {
        val plan = largeLayoutPlan(NowPlayingLayout.Landscape, pad6LandscapeArea.first, pad6LandscapeArea.second)
        assertThat(plan.controlsScale).isAtLeast(1.25f)
        assertThat(plan.controlsScale).isAtMost(MaxLargeControlsScale)
    }

    @Test
    fun `the Pad 6 landscape artwork and controls fit side by side with margins and the artwork leads`() {
        val (width, height) = pad6LandscapeArea
        val plan = largeLayoutPlan(NowPlayingLayout.Landscape, width, height)
        assertThat((plan.sidePadding * 2f + plan.artworkSide + plan.columnGap + plan.controlsWidth).value).isAtMost(width.value + Tolerance)
        assertThat(plan.artworkSide).isAtMost(height)
        assertThat(plan.artworkSide).isGreaterThan(plan.controlsWidth)
        assertThat(plan.artworkSide.value).isAtLeast(500f)
    }

    @Test
    fun `a short landscape window limits the artwork by its height and leaves it room above and below`() {
        val plan = largeLayoutPlan(NowPlayingLayout.Landscape, 960.dp, 480.dp)
        assertThat(plan.artworkSide.value).isAtMost(480f - 2 * 16f)
    }

    @Test
    fun `a wider landscape window gives the controls more room, up to a cap`() {
        val narrow = largeLayoutPlan(NowPlayingLayout.Landscape, 900.dp, 600.dp)
        val wide = largeLayoutPlan(NowPlayingLayout.Landscape, 1280.dp, 800.dp)
        val huge = largeLayoutPlan(NowPlayingLayout.Landscape, 2400.dp, 1200.dp)
        assertThat(wide.controlsWidth).isGreaterThan(narrow.controlsWidth)
        assertThat(huge.controlsWidth).isEqualTo(MaxLargeControlsWidth)
        assertThat(huge.artworkSide).isAtMost(MaxLargeArtworkSide)
    }

    @Test
    fun `the landscape controls scale is capped so the controls always fit the height`() {
        val plan = largeLayoutPlan(NowPlayingLayout.Landscape, 1280.dp, 330.dp)
        assertThat(plan.controlsScale * LandscapeControlsMinHeight.value).isAtMost(330f + Tolerance)
        assertThat(plan.controlsScale).isAtLeast(1f)
    }

    // ---- Portrait: the artwork above, capped so the controls fit

    // The Pad 6 in portrait less the status bar, the handle row and the bottom margin.
    private val pad6PortraitArea = Pair(720.dp, 1095.dp)

    @Test
    fun `the Pad 6 portrait controls are scaled up and as wide as the artwork`() {
        val (width, height) = pad6PortraitArea
        val plan = largeLayoutPlan(NowPlayingLayout.Portrait, width, height)
        assertThat(plan.controlsScale).isAtLeast(1.25f)
        assertThat(plan.controlsWidth).isEqualTo(plan.artworkSide)
        assertThat((plan.sidePadding * 2f + plan.controlsWidth).value).isAtMost(width.value + Tolerance)
    }

    @Test
    fun `the Pad 6 portrait artwork and controls fit the height`() {
        val (width, height) = pad6PortraitArea
        val plan = largeLayoutPlan(NowPlayingLayout.Portrait, width, height)
        assertThat(plan.artworkSide.value + PortraitControlsHeight.value * plan.controlsScale).isAtMost(height.value + Tolerance)
    }

    @Test
    fun `a short portrait window shrinks the controls scale before it squeezes the artwork`() {
        val tall = largeLayoutPlan(NowPlayingLayout.Portrait, 720.dp, 1095.dp)
        val short = largeLayoutPlan(NowPlayingLayout.Portrait, 720.dp, 700.dp)
        assertThat(short.controlsScale).isLessThan(tall.controlsScale)
        assertThat(short.artworkSide.value).isAtLeast(MinPortraitArtworkSide.value - Tolerance)
    }

    @Test
    fun `no window scales the controls below phone size or above the cap`() {
        for ((width, height) in listOf(600 to 600, 600 to 700, 640 to 600, 720 to 1152, 1152 to 720, 2400 to 1200)) {
            for (layout in NowPlayingLayout.entries) {
                val plan = largeLayoutPlan(layout, width.dp, height.dp)
                assertThat(plan.controlsScale).isAtLeast(1f)
                assertThat(plan.controlsScale).isAtMost(MaxLargeControlsScale)
                assertThat(plan.artworkSide.value).isAtLeast(0f)
                assertThat(plan.controlsWidth.value).isAtLeast(0f)
            }
        }
    }

    // ---- Touch targets

    @Test
    fun `a control drawn scaled up keeps at least 44 dp on screen`() {
        assertThat(touchTargetSide(1f).value).isEqualTo(44f)
        assertThat(touchTargetSide(1.3f).value * 1.3f).isAtLeast(44f)
        assertThat(touchTargetSide(1.5f).value * 1.5f).isAtLeast(44f)
    }
}
