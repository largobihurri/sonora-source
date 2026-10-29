package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapsuleDragTest {

    private val changes = mutableListOf<Float>()
    private val finished = mutableListOf<Float>()
    private var cancelled = 0
    private val draggingEvents = mutableListOf<Boolean>()
    private var current = 0.2f
    private val drag = CapsuleDrag(
        currentValue = { current },
        onChange = { changes += it },
        onFinished = { finished += it },
        onCancelled = { cancelled++ },
        onDragging = { draggingEvents += it },
    )

    @Test
    fun `release reports the final value`() {
        drag.start()
        drag.dragBy(0.3f)
        drag.end()
        assertThat(changes).containsExactly(0.2f, 0.5f).inOrder()
        assertThat(finished).containsExactly(0.5f)
        assertThat(cancelled).isEqualTo(0)
        assertThat(draggingEvents).containsExactly(true, false).inOrder()
        assertThat(drag.dragging).isFalse()
    }

    @Test
    fun `cancel ends the drag without committing a value`() {
        current = 0.4f
        drag.start()
        drag.dragBy(0.1f)
        drag.cancel()
        assertThat(finished).isEmpty()
        assertThat(cancelled).isEqualTo(1)
        assertThat(draggingEvents).containsExactly(true, false).inOrder()
        assertThat(drag.dragging).isFalse()
    }

    @Test
    fun `end or cancel without a drag in progress reports nothing`() {
        drag.end()
        drag.cancel()
        assertThat(finished).isEmpty()
        assertThat(cancelled).isEqualTo(0)
        assertThat(draggingEvents).isEmpty()
    }

    @Test
    fun `a second end or cancel after the drag finished reports nothing`() {
        current = 0.3f
        drag.start()
        drag.end()
        drag.end()
        drag.cancel()
        assertThat(finished).containsExactly(0.3f)
        assertThat(cancelled).isEqualTo(0)
        assertThat(draggingEvents).containsExactly(true, false).inOrder()
    }

    @Test
    fun `value stays within bounds`() {
        current = 1.4f
        drag.start()
        drag.dragBy(-3f)
        drag.end()
        assertThat(changes).containsExactly(1f, 0f).inOrder()
        assertThat(finished).containsExactly(0f)
    }

    @Test
    fun `a drag moves the value only by the finger's travel from the current value`() {
        // Volume at 20 %: touching anywhere and sliding 20 % of the track ends at 40 %, never at the touch point.
        current = 0.2f
        drag.start()
        drag.dragBy(0.1f)
        drag.dragBy(0.1f)
        drag.end()
        assertThat(changes).containsExactly(0.2f, 0.3f, 0.4f).inOrder()
        assertThat(finished.single()).isWithin(1e-6f).of(0.4f)
    }

    @Test
    fun `the finger's travel before the drag was recognised counts, so the value tracks it 1 to 1`() {
        // Touch slop: the finger travels 8 % of the track before the drag starts, then 2 % more: 20 % -> 30 %.
        current = 0.2f
        drag.start(travelBeforeStart = 0.08f)
        drag.dragBy(0.02f)
        drag.end()
        assertThat(changes.first()).isWithin(1e-6f).of(0.28f)
        assertThat(finished.single()).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `travel before the start is clamped like any other`() {
        current = 0.97f
        drag.start(travelBeforeStart = 0.08f)
        drag.end()
        assertThat(finished).containsExactly(1f)
    }

    @Test
    fun `each drag starts from the value current at that moment`() {
        current = 0.5f
        drag.start()
        drag.dragBy(0.1f)
        drag.end()
        current = 0.7f
        drag.start()
        drag.dragBy(-0.2f)
        drag.end()
        assertThat(finished[1]).isWithin(1e-6f).of(0.5f)
    }
}
