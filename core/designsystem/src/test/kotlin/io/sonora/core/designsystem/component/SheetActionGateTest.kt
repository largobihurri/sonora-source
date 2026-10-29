package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceUntilIdle
class SheetActionGateTest {

    private val gate = SheetActionGate()
    private val ran = mutableListOf<String>()
    private val slide: suspend () -> Unit = { delay(300) }

    @Test
    fun `a double tap or a second row tapped while the sheet slides away runs one action`() = runTest {
        launch { gate.choose(slide) { ran += "Play Next" } }
        launch { gate.choose(slide) { ran += "Play Next" } }
        launch { gate.choose(slide) { ran += "Add to End of Queue" } }

        advanceUntilIdle()

        assertThat(ran).containsExactly("Play Next")
    }

    @Test
    fun `taps after the action ran are ignored`() = runTest {
        assertThat(gate.choose({}) { ran += "first" }).isTrue()

        assertThat(gate.choose({}) { ran += "second" }).isFalse()
        assertThat(ran).containsExactly("first")
    }

    @Test
    fun `an interrupted slide runs nothing and lets the next tap choose`() = runTest {
        val interrupted = launch { gate.choose({ awaitCancellation() }) { ran += "first" } }
        runCurrent()

        interrupted.cancel()
        runCurrent()

        assertThat(ran).isEmpty()
        assertThat(gate.choose({}) { ran += "second" }).isTrue()
        assertThat(ran).containsExactly("second")
    }

    @Test
    fun `a menu's choice runs within the tap, and a double tap or a second row runs nothing more`() = runTest {
        val choose = { label: String -> gate.chooseNow(this) { ran += listOf(label, "dismiss") } }
        choose("Delete Playlist")

        // Within the tap: nothing waits for the dispatcher.
        assertThat(ran).containsExactly("Delete Playlist", "dismiss").inOrder()

        choose("Delete Playlist")
        choose("Rename")
        advanceUntilIdle()

        assertThat(ran).containsExactly("Delete Playlist", "dismiss").inOrder()
    }
}
