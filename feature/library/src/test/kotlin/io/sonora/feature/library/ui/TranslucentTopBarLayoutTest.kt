package io.sonora.feature.library.ui

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentTopBarLayoutTest {

    @Test
    fun `the title may take the whole room between the side buttons`() {
        // 400 px bar, a 120 px leading capsule and a 70 px trailing one, 8 px gaps: 400 - 128 - 78 = 194 px.
        assertThat(titleRoom(barWidth = 400, leading = 120, trailing = 70, gap = 8)).isEqualTo(194)
    }

    @Test
    fun `the title takes the whole bar when there are no side buttons`() {
        assertThat(titleRoom(barWidth = 400, leading = 0, trailing = 0, gap = 8)).isEqualTo(400)
    }

    @Test
    fun `side buttons wider than the bar leave no room rather than a negative width`() {
        assertThat(titleRoom(barWidth = 300, leading = 200, trailing = 120, gap = 8)).isEqualTo(0)
    }

    @Test
    fun `a title that fits stays centred, a wider one moves toward the narrower side instead of being cut`() {
        // Centred, a 100 px title starts at 150 and clears the 128 px leading side.
        assertThat(titleStart(barWidth = 400, leading = 120, trailing = 70, gap = 8, title = 100)).isEqualTo(150)
        // A 180 px title centred would start at 110, under the leading capsule: it starts right after it instead.
        assertThat(titleStart(barWidth = 400, leading = 120, trailing = 70, gap = 8, title = 180)).isEqualTo(128)
        // The same on the other side when the trailing buttons are the wider ones.
        assertThat(titleStart(barWidth = 400, leading = 70, trailing = 120, gap = 8, title = 180)).isEqualTo(92)
    }

    @Test
    fun `a bar is the translucent button plus the same inset above and below`() {
        assertThat(translucentBarHeight(button = 44.dp, inset = 4.dp)).isEqualTo(52.dp)
    }

    @Test
    fun `the scroll edge is solid behind the bar's text and buttons`() {
        assertThat(scrollEdgeAlpha(0.dp)).isEqualTo(1f)
        assertThat(scrollEdgeAlpha(44.dp)).isEqualTo(1f)
    }

    @Test
    fun `the scroll edge fades out over the bar's bottom inset and is gone at the bar's bottom edge`() {
        assertThat(scrollEdgeAlpha(48.dp)).isWithin(1e-4f).of(0.5f)
        assertThat(scrollEdgeAlpha(TranslucentTopBarHeight)).isEqualTo(0f)
        assertThat(scrollEdgeAlpha(60.dp)).isEqualTo(0f)
    }

    @Test
    fun `the scroll edge never gets more opaque going down`() {
        var previous = scrollEdgeAlpha(0.dp)
        for (y in 1..80) {
            val alpha = scrollEdgeAlpha(y.dp)
            assertThat(alpha).isAtMost(previous)
            previous = alpha
        }
    }

    @Test
    fun `the scroll edge ends within the bar so the first item at scroll 0 is not veiled`() {
        assertThat(ScrollEdgeHeight).isAtMost(TranslucentTopBarHeight)
    }
}
