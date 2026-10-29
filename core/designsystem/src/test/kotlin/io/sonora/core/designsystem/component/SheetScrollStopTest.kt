package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SheetScrollStopTest {

    private val down = 40f
    private val up = -40f

    @Test
    fun `a gesture that started at the list's top hands its downward drag to the sheet`() {
        assertThat(keepsFromSheet(gestureStartedAtTop = true, flingStartedAtTop = true, fromFling = false, delta = down)).isFalse()
    }

    @Test
    fun `a gesture that started below the top keeps its downward drag from the sheet`() {
        assertThat(keepsFromSheet(gestureStartedAtTop = false, flingStartedAtTop = false, fromFling = false, delta = down)).isTrue()
    }

    @Test
    fun `a fling that started below the top keeps its downward leftovers from the sheet`() {
        assertThat(keepsFromSheet(gestureStartedAtTop = true, flingStartedAtTop = false, fromFling = true, delta = down)).isTrue()
        assertThat(keepsFromSheet(gestureStartedAtTop = false, flingStartedAtTop = false, fromFling = true, delta = down)).isTrue()
    }

    @Test
    fun `a fling at the top of a gesture that started below it keeps its leftovers from the sheet`() {
        // Dragged to the top (the sheet held still), then flung down from there: the sheet stays.
        assertThat(keepsFromSheet(gestureStartedAtTop = false, flingStartedAtTop = true, fromFling = true, delta = down)).isTrue()
    }

    @Test
    fun `a fling from the top of a gesture that started there hands its velocity to the sheet`() {
        assertThat(keepsFromSheet(gestureStartedAtTop = true, flingStartedAtTop = true, fromFling = true, delta = down)).isFalse()
    }

    @Test
    fun `upward leftovers and no leftovers always pass`() {
        for (startedAtTop in listOf(true, false)) {
            for (fromFling in listOf(true, false)) {
                assertThat(keepsFromSheet(startedAtTop, startedAtTop, fromFling, delta = up)).isFalse()
                assertThat(keepsFromSheet(startedAtTop, startedAtTop, fromFling, delta = 0f)).isFalse()
            }
        }
    }
}
