package io.sonora.core.designsystem.motion

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A tab's stack as the app shell sees it: its depth, whether Back reaches it, and the way back to its root. */
class TabStackTest {

    private val stack = mutableListOf("Library", "Albums", "Abbey Road", "Settings")
    private val library = TabStack.RegisteredStack { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    @Test
    fun `popping to the root pops every screen above the first one at once`() {
        val tab = TabStack()
        tab.attach(library, stack.size, backEnabled = true)
        assertThat(tab.depth).isEqualTo(4)

        assertThat(tab.popToRoot()).isTrue()

        assertThat(stack).containsExactly("Library")
        assertThat(tab.depth).isEqualTo(1) // until the stack reports itself again
        assertThat(tab.popToRoot()).isFalse() // a second tap before that pops nothing more
        assertThat(stack).containsExactly("Library")
    }

    @Test
    fun `a stack at its root, or none at all, has nothing to pop`() {
        val none = TabStack()
        assertThat(none.depth).isEqualTo(1)
        assertThat(none.backEnabled).isTrue()
        assertThat(none.popToRoot()).isFalse()

        val root = TabStack()
        root.attach(library, 1, backEnabled = true)
        assertThat(root.popToRoot()).isFalse()
        assertThat(stack).hasSize(4)
    }

    @Test
    fun `a stack whose Back is held elsewhere is left as it is, as Back leaves it`() {
        // E.g. the Now Playing sheet open above the Library owns Back: the Library stack keeps its screens.
        val tab = TabStack()
        tab.attach(library, stack.size, backEnabled = false)
        assertThat(tab.depth).isEqualTo(4)
        assertThat(tab.backEnabled).isFalse()

        assertThat(tab.popToRoot()).isFalse()
        assertThat(stack).hasSize(4)

        tab.attach(library, stack.size, backEnabled = true) // the sheet closed: Back reaches the stack again
        assertThat(tab.popToRoot()).isTrue()
        assertThat(stack).containsExactly("Library")
    }

    @Test
    fun `a stack that left the screen is forgotten, and leaving late does not unregister a newer one`() {
        val tab = TabStack()
        tab.attach(library, stack.size, backEnabled = true)
        tab.detach(library)
        assertThat(tab.depth).isEqualTo(1)
        assertThat(tab.popToRoot()).isFalse()
        assertThat(stack).hasSize(4)

        val newer = TabStack.RegisteredStack { stack.clear() }
        tab.attach(newer, 2, backEnabled = true)
        tab.detach(library) // the old stack leaving again does not unregister the new one
        assertThat(tab.depth).isEqualTo(2)
    }

    @Test
    fun `an older stack reporting itself again does not take the tab from the newer one`() {
        val other = mutableListOf("Main", "Picker")
        val newer = TabStack.RegisteredStack { if (other.size > 1) other.removeAt(other.lastIndex) }
        val tab = TabStack()
        tab.attach(library, stack.size, backEnabled = true)
        tab.attach(newer, other.size, backEnabled = true)

        tab.attach(library, stack.size, backEnabled = true) // the older one recomposes

        assertThat(tab.depth).isEqualTo(2)
        assertThat(tab.popToRoot()).isTrue()
        assertThat(other).containsExactly("Main")
        assertThat(stack).hasSize(4)
    }

    @Test
    fun `when the stack holding the tab leaves, the one still on screen holds it again at once`() {
        val other = mutableListOf("Main", "Picker")
        val newer = TabStack.RegisteredStack { if (other.size > 1) other.removeAt(other.lastIndex) }
        val tab = TabStack()
        tab.attach(library, stack.size, backEnabled = true)
        tab.attach(newer, other.size, backEnabled = true)

        tab.detach(newer)

        assertThat(tab.depth).isEqualTo(4)
        assertThat(tab.popToRoot()).isTrue()
        assertThat(stack).containsExactly("Library")
        assertThat(other).hasSize(2)
    }

    // The registration as PredictiveBackStack drives it, through a real composition.

    @Test
    fun `the outermost stack registers itself, the stacks nested in it do not, and leaving unregisters it`() = runTest {
        val tab = TabStack()
        val outer = mutableStateListOf("Library", "Albums", "Abbey Road")
        val inner = mutableListOf("Main", "Picker", "Folder")
        var shown by mutableStateOf(true)
        var backEnabled by mutableStateOf(true)
        compose {
            CompositionLocalProvider(LocalTabStack provides tab) {
                if (shown) {
                    TabStackRegistration(outer.size, backEnabled, onPop = { outer.removeAt(outer.lastIndex) }) {
                        TabStackRegistration(inner.size, true, onPop = { inner.removeAt(inner.lastIndex) }) {}
                    }
                }
            }
        }
        assertThat(tab.depth).isEqualTo(3)

        outer.add("Settings")
        recompose()
        assertThat(tab.depth).isEqualTo(4)

        backEnabled = false
        recompose()
        assertThat(tab.backEnabled).isFalse()
        assertThat(tab.popToRoot()).isFalse()
        assertThat(outer).hasSize(4)

        backEnabled = true
        recompose()
        assertThat(tab.popToRoot()).isTrue()
        assertThat(outer).containsExactly("Library")
        assertThat(inner).hasSize(3) // the nested stack stays put
        recompose()
        assertThat(tab.depth).isEqualTo(1)

        outer.add("Albums")
        recompose()
        shown = false
        recompose()
        assertThat(tab.depth).isEqualTo(1)
        assertThat(tab.popToRoot()).isFalse()
        assertThat(outer).hasSize(2)
    }

    private lateinit var clock: BroadcastFrameClock

    private fun TestScope.compose(content: @Composable () -> Unit) {
        clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock) // ends with the test
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { composition.dispose() }
        composition.setContent(content)
    }

    private fun TestScope.recompose() {
        Snapshot.sendApplyNotifications()
        runCurrent()
        clock.sendFrame(0L)
        runCurrent()
    }

    /** These compositions only run effects; they emit no nodes. */
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
