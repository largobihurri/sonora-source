package io.sonora.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class TranslucentPillTest {

    /**
     * The shortest gap, in dp, between a capsule [height] tall and a leading thumbnail [size] square with [corner]
     * corners that starts [start] from the capsule's leading edge and is centred vertically.
     */
    private fun clearance(height: Dp, size: Dp, corner: Dp, start: Dp): Float {
        val radius = height.value / 2f
        val top = (height.value - size.value) / 2f
        val r = corner.value
        var shortest = Float.MAX_VALUE
        // The thumbnail's left half: its two left corner arcs and its straight edges.
        val corners = listOf(start.value + r to top + r, start.value + r to top + size.value - r)
        for ((cx, cy) in corners) {
            for (step in 0..360) {
                val angle = step * PI / 180.0
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                // Distance from the point to the capsule's outline: its round end, or its straight top and bottom.
                val toEnd = if (x < radius) radius - hypot(x - radius, y - radius) else Float.MAX_VALUE
                shortest = minOf(shortest, toEnd, y, height.value - y)
            }
        }
        return shortest
    }

    @Test
    fun `the inset is the gap above and below the thumbnail`() {
        assertThat(pillInset(48.dp, 36.dp)).isEqualTo(6.dp)
        assertThat(pillInset(32.dp, 40.dp)).isEqualTo(0.dp)
    }

    @Test
    fun `a round thumbnail sits the plain inset from the round end`() {
        assertThat(pillLeadingInset(48.dp, 36.dp, 18.dp)).isEqualTo(6.dp)
        assertThat(clearance(48.dp, 36.dp, 18.dp, pillLeadingInset(48.dp, 36.dp, 18.dp))).isWithin(0.01f).of(6f)
    }

    @Test
    fun `a rounded-square thumbnail moves in until its corners clear the round end by the same inset`() {
        val start = pillLeadingInset(48.dp, 36.dp, 8.dp)

        assertThat(start).isEqualTo(16.dp)
        assertThat(clearance(48.dp, 36.dp, 8.dp, start)).isWithin(0.01f).of(6f)
        // The old plain inset let the corners come much closer than the top and bottom gaps.
        assertThat(clearance(48.dp, 36.dp, 8.dp, 6.dp)).isLessThan(3f)
    }

    @Test
    fun `a square-cornered thumbnail starts where the round end does`() {
        assertThat(pillLeadingInset(48.dp, 36.dp, 0.dp)).isEqualTo(24.dp)
    }

    @Test
    fun `corners wider than half the thumbnail count as a round thumbnail`() {
        assertThat(pillLeadingInset(44.dp, 32.dp, 30.dp)).isEqualTo(pillInset(44.dp, 32.dp))
    }
}
