package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DragSelectTest {

    private val order = listOf("a", "b", "c", "d", "e")

    private fun paint(picked: Set<String>, anchor: String) = DragPaint.begin(order, picked, anchor)!!

    @Test
    fun `dragging down from an unpicked row picks every row down to the finger`() {
        val drag = paint(emptySet(), "b")
        assertThat(drag.selecting).isTrue()
        assertThat(drag.extendTo("d")).containsExactly("b", "c", "d")
    }

    @Test
    fun `dragging up from an unpicked row picks every row up to the finger`() {
        assertThat(paint(emptySet(), "d").extendTo("b")).containsExactly("b", "c", "d")
    }

    @Test
    fun `dragging from a picked row unpicks the rows it passes`() {
        val drag = paint(setOf("a", "b", "c", "d"), "b")
        assertThat(drag.selecting).isFalse()
        assertThat(drag.extendTo("d")).containsExactly("a")
    }

    @Test
    fun `a drag that stays on its row is the tap on that row`() {
        assertThat(paint(emptySet(), "c").extendTo("c")).containsExactly("c")
        assertThat(paint(setOf("c", "d"), "c").extendTo("c")).containsExactly("d")
        assertThat(SelectionState().start().toggle("c").keys).isEqualTo(paint(emptySet(), "c").extendTo("c"))
    }

    @Test
    fun `moving back toward the anchor restores the rows the finger left, including rows picked before`() {
        val drag = paint(setOf("c"), "a")
        assertThat(drag.extendTo("e")).containsExactly("a", "b", "c", "d", "e")
        assertThat(drag.extendTo("b")).containsExactly("a", "b", "c")
        assertThat(drag.extendTo("a")).containsExactly("a", "c")
    }

    @Test
    fun `moving back restores rows that were picked before when unpicking`() {
        val drag = paint(setOf("a", "b", "c", "d", "e"), "b")
        assertThat(drag.extendTo("d")).containsExactly("a", "e")
        assertThat(drag.extendTo("c")).containsExactly("a", "d", "e")
        assertThat(drag.extendTo("b")).containsExactly("a", "c", "d", "e")
    }

    @Test
    fun `crossing the anchor paints the other side and restores the first`() {
        val drag = paint(emptySet(), "c")
        assertThat(drag.extendTo("e")).containsExactly("c", "d", "e")
        assertThat(drag.extendTo("a")).containsExactly("a", "b", "c")
    }

    @Test
    fun `a row outside the list leaves the painting as it was`() {
        val drag = paint(emptySet(), "b")
        drag.extendTo("c")
        assertThat(drag.extendTo("gone")).containsExactly("b", "c")
    }

    @Test
    fun `a drag cannot start from a row that is not listed`() {
        assertThat(DragPaint.begin(order, emptySet(), "gone")).isNull()
    }

    @Test
    fun `the row under the finger is the one whose span holds it`() {
        val rows = listOf(RowSpan("a", 0f, 60f), RowSpan("b", 60f, 120f))
        assertThat(rowContaining(59f, rows)).isEqualTo("a")
        assertThat(rowContaining(60f, rows)).isEqualTo("b")
        assertThat(rowContaining(120f, rows)).isNull()
    }

    @Test
    fun `past the ends or between sections the nearest row is the one under the finger`() {
        val rows = listOf(RowSpan("a", 100f, 160f), RowSpan("b", 160f, 220f), RowSpan("c", 300f, 360f))
        assertThat(nearestRow(10f, rows)).isEqualTo("a")
        assertThat(nearestRow(900f, rows)).isEqualTo("c")
        assertThat(nearestRow(250f, rows)).isEqualTo("b")
        assertThat(nearestRow(280f, rows)).isEqualTo("c")
        assertThat(nearestRow(50f, emptyList())).isNull()
    }

    @Test
    fun `only a press on the trailing circle zone starts a selecting drag`() {
        assertThat(inCircleZone(x = 1000f, width = 1080f, zone = 140f)).isTrue()
        assertThat(inCircleZone(x = 939f, width = 1080f, zone = 140f)).isFalse()
        assertThat(inCircleZone(x = 540f, width = 1080f, zone = 140f)).isFalse()
    }

    @Test
    fun `auto-scroll is still outside the edge zones`() {
        assertThat(autoScrollSpeed(y = 500f, top = 100f, bottom = 900f, zone = 100f, maxSpeed = 2000f)).isEqualTo(0f)
        assertThat(autoScrollSpeed(y = 200f, top = 100f, bottom = 900f, zone = 100f, maxSpeed = 2000f)).isEqualTo(0f)
        assertThat(autoScrollSpeed(y = 800f, top = 100f, bottom = 900f, zone = 100f, maxSpeed = 2000f)).isEqualTo(0f)
    }

    @Test
    fun `auto-scroll grows toward the edge, up at the top and down at the bottom`() {
        fun speed(y: Float) = autoScrollSpeed(y, top = 100f, bottom = 900f, zone = 100f, maxSpeed = 2000f)
        assertThat(speed(150f)).isLessThan(0f)
        assertThat(speed(120f)).isLessThan(speed(150f))
        assertThat(speed(850f)).isGreaterThan(0f)
        assertThat(speed(880f)).isGreaterThan(speed(850f))
    }

    @Test
    fun `auto-scroll is at full speed on the edge and past it`() {
        fun speed(y: Float) = autoScrollSpeed(y, top = 100f, bottom = 900f, zone = 100f, maxSpeed = 2000f)
        assertThat(speed(100f)).isEqualTo(-2000f)
        assertThat(speed(-300f)).isEqualTo(-2000f)
        assertThat(speed(900f)).isEqualTo(2000f)
        assertThat(speed(1500f)).isEqualTo(2000f)
    }

    @Test
    fun `a viewport shorter than two zones splits between them`() {
        assertThat(autoScrollSpeed(y = 440f, top = 400f, bottom = 500f, zone = 100f, maxSpeed = 1000f)).isLessThan(0f)
        assertThat(autoScrollSpeed(y = 460f, top = 400f, bottom = 500f, zone = 100f, maxSpeed = 1000f)).isGreaterThan(0f)
        assertThat(autoScrollSpeed(y = 450f, top = 400f, bottom = 500f, zone = 100f, maxSpeed = 1000f)).isEqualTo(0f)
        assertThat(autoScrollSpeed(y = 450f, top = 500f, bottom = 400f, zone = 100f, maxSpeed = 1000f)).isEqualTo(0f)
    }

    @Test
    fun `painting sets the picked rows only while selecting`() {
        assertThat(SelectionState().start().paint(setOf("a", "b")).keys).containsExactly("a", "b")
        assertThat(SelectionState().paint(setOf("a"))).isEqualTo(SelectionState())
    }
}
