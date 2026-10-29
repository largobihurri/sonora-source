package io.sonora.feature.settings.duplicates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Widths in dp: the 2026-10-05 Realme is 320 dp wide, which leaves the copies 176 dp beside 80 dp of labels. */
class ComparisonColumnsTest {

    private val min = 88f
    private val max = 132f

    @Test
    fun `two copies share a phone's room and need no scrolling`() {
        assertThat(ComparisonColumns.width(available = 176f, copies = 2, min = min, max = max)).isEqualTo(88f)
    }

    @Test
    fun `columns never grow past the widest`() {
        assertThat(ComparisonColumns.width(available = 600f, copies = 2, min = min, max = max)).isEqualTo(132f)
    }

    @Test
    fun `copies that do not fit split the room into whole columns`() {
        // Three copies at 88 dp need 264 dp: two whole columns show, each 88 dp, so no copy is cut at an edge.
        assertThat(ComparisonColumns.width(available = 176f, copies = 3, min = min, max = max)).isEqualTo(88f)
        // 200 dp holds two 88 dp columns: they widen to 100 dp each and fill it exactly.
        assertThat(ComparisonColumns.width(available = 200f, copies = 3, min = min, max = max)).isEqualTo(100f)
    }

    @Test
    fun `a room between one and two columns is not capped, so no copy is cut`() {
        // 150 dp holds one 88 dp column: it widens to the whole room, not to 132 dp with 18 dp of copy 2 showing.
        assertThat(ComparisonColumns.width(available = 150f, copies = 2, min = min, max = max)).isEqualTo(150f)
        assertThat(ComparisonColumns.width(available = 175f, copies = 2, min = min, max = max)).isEqualTo(175f)
        assertThat(ComparisonColumns.width(available = 132f, copies = 2, min = min, max = max)).isEqualTo(132f)
        assertThat(ComparisonColumns.width(available = 140f, copies = 3, min = min, max = max)).isEqualTo(140f)
    }

    @Test
    fun `at any room every copy is whole at a column edge or the copies fit side by side`() {
        for (copies in 2..5) {
            for (room in 40..400) {
                val available = room.toFloat()
                val width = ComparisonColumns.width(available, copies, min, max)
                val columns = available / width
                val wholeColumns = abs(columns - columns.roundToInt()) < 1e-4f
                assertWithMessage("available $available, $copies copies, width $width")
                    .that(wholeColumns || width * copies <= available)
                    .isTrue()
            }
        }
    }

    @Test
    fun `a room narrower than one column still shows one whole copy`() {
        assertThat(ComparisonColumns.width(available = 70f, copies = 2, min = min, max = max)).isEqualTo(70f)
    }

    @Test
    fun `a release settles on the nearest column edge`() {
        assertThat(ComparisonColumns.snapDelta(scroll = 30f, column = 100f, maxScroll = 200f, velocity = 0f)).isEqualTo(-30f)
        assertThat(ComparisonColumns.snapDelta(scroll = 70f, column = 100f, maxScroll = 200f, velocity = 0f)).isEqualTo(30f)
    }

    @Test
    fun `a fling settles on the next column edge in its direction`() {
        assertThat(ComparisonColumns.snapDelta(scroll = 30f, column = 100f, maxScroll = 200f, velocity = 500f)).isEqualTo(70f)
        assertThat(ComparisonColumns.snapDelta(scroll = 170f, column = 100f, maxScroll = 200f, velocity = -500f)).isEqualTo(-70f)
    }

    @Test
    fun `settling never passes the ends`() {
        // The last edge past the end is cut to the end, where the last copy shows whole.
        assertThat(ComparisonColumns.snapDelta(scroll = 150f, column = 100f, maxScroll = 160f, velocity = 500f)).isEqualTo(10f)
        assertThat(ComparisonColumns.snapDelta(scroll = 0f, column = 100f, maxScroll = 160f, velocity = -500f)).isEqualTo(0f)
    }

    @Test
    fun `a scroll already on an edge stays`() {
        assertThat(ComparisonColumns.snapDelta(scroll = 100f, column = 100f, maxScroll = 200f, velocity = 0f)).isEqualTo(0f)
    }
}
