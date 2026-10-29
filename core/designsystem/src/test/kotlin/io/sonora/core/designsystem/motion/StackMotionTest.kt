package io.sonora.core.designsystem.motion

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The predictive back stack's state rules (the composable only animates [StackMotion.progress]). */
class StackMotionTest {

    private val discarded = mutableListOf<String>()

    private suspend fun StackMotion<String>.changeTo(vararg target: String, reduced: Boolean = false) =
        change(target.toList(), reduced) { discarded += it }

    private val StackMotion<String>.onStage get() = layers.map { it.entry }

    @Test
    fun `a push puts the new screen on top of the previous one, then settles on it`() = runTest {
        val motion = StackMotion(listOf("Library"))

        assertThat(motion.changeTo("Library", "Albums")).isEqualTo(StackAnimation.Push)

        assertThat(motion.onStage).containsExactly("Library", "Albums").inOrder()
        assertThat(motion.progress.value).isEqualTo(1f) // the push slides in from fully off
        assertThat(motion.shown).containsExactly("Library", "Albums").inOrder()
        motion.settle(listOf("Library", "Albums"))
        assertThat(motion.onStage).containsExactly("Albums")
        assertThat(motion.progress.value).isEqualTo(0f)
        assertThat(discarded).isEmpty()
    }

    @Test
    fun `nested pushes stack each screen over the last one and pops walk back`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.changeTo("Library", "Folders")
        motion.settle(listOf("Library", "Folders"))

        assertThat(motion.changeTo("Library", "Folders", "Rock")).isEqualTo(StackAnimation.Push)
        assertThat(motion.onStage).containsExactly("Folders", "Rock").inOrder()
        motion.settle(listOf("Library", "Folders", "Rock"))

        assertThat(motion.changeTo("Library", "Folders")).isEqualTo(StackAnimation.Pop)
        assertThat(motion.onStage).containsExactly("Folders", "Rock").inOrder()
        assertThat(motion.shown).hasSize(3) // the popped screen stays until the pop finished
        assertThat(discarded).containsExactly("2:Rock")
        motion.settle(listOf("Library", "Folders"))
        assertThat(motion.onStage).containsExactly("Folders")
    }

    @Test
    fun `the same screen twice in the stack keeps two saved states`() = runTest {
        val motion = StackMotion(listOf("Library", "Rock"))
        motion.changeTo("Library", "Rock", "Rock")
        motion.settle(listOf("Library", "Rock", "Rock"))

        motion.changeTo("Library", "Rock")

        assertThat(discarded).containsExactly("2:Rock")
        assertThat(motion.layers.map { it.stateKey }).containsExactly("1:Rock", "2:Rock").inOrder()
    }

    @Test
    fun `the back gesture moves the top screen with the finger from its edge`() = runTest {
        val motion = StackMotion(listOf("Library", "Albums"))

        motion.gestureMoved(listOf("Library", "Albums"), 0.4f, SwipeEdge.Right)

        assertThat(motion.onStage).containsExactly("Library", "Albums").inOrder()
        assertThat(motion.pair?.edge).isEqualTo(SwipeEdge.Right)
        assertThat(motion.progress.value).isEqualTo(0.4f)
        assertThat(stackFrame(motion.progress.value, motion.pair!!.edge).topOffset).isWithin(1e-6f).of(-0.4f)
        motion.gestureMoved(listOf("Library", "Albums"), 0.7f, SwipeEdge.Right)
        assertThat(motion.progress.value).isEqualTo(0.7f)
    }

    @Test
    fun `a committed gesture pops without animating the pop a second time`() = runTest {
        val motion = StackMotion(listOf("Library", "Albums"))
        motion.gestureMoved(listOf("Library", "Albums"), 0.6f, SwipeEdge.Left)
        motion.progress.snapTo(1f) // the composable finishes the motion
        motion.gestureFinished()

        assertThat(motion.changeTo("Library")).isEqualTo(StackAnimation.None)
        assertThat(discarded).containsExactly("1:Albums")
        motion.settle(listOf("Library"))

        assertThat(motion.onStage).containsExactly("Library")
        assertThat(motion.progress.value).isEqualTo(0f)
        // Only that one pop skips its animation.
        motion.changeTo("Library", "Songs")
        motion.settle(listOf("Library", "Songs"))
        assertThat(motion.changeTo("Library")).isEqualTo(StackAnimation.Pop)
    }

    @Test
    fun `a cancelled gesture springs back to the unchanged stack`() = runTest {
        val motion = StackMotion(listOf("Library", "Albums"))
        motion.gestureMoved(listOf("Library", "Albums"), 0.5f, SwipeEdge.Left)

        motion.progress.snapTo(0f) // the composable's spring back
        motion.settle(listOf("Library", "Albums"))

        assertThat(motion.onStage).containsExactly("Albums")
        assertThat(motion.shown).containsExactly("Library", "Albums").inOrder()
        assertThat(discarded).isEmpty()
        assertThat(motion.changeTo("Library")).isEqualTo(StackAnimation.Pop) // the next pop animates as usual
    }

    @Test
    fun `a gesture cancelled during a push settles on the stack that is current then`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.changeTo("Library", "Albums") // push running
        motion.gestureMoved(listOf("Library", "Albums"), 0.3f, SwipeEdge.Left)

        motion.settle(listOf("Library", "Albums"))

        assertThat(motion.pair).isNull()
        assertThat(motion.onStage).containsExactly("Albums")
    }

    @Test
    fun `with animations removed, and for jumps to another branch, screens switch without motion`() = runTest {
        val reduced = StackMotion(listOf("Library"))
        assertThat(reduced.changeTo("Library", "Albums", reduced = true)).isEqualTo(StackAnimation.None)
        assertThat(reduced.pair).isNull()

        val deep = StackMotion(listOf("Library", "Folders", "Rock"))
        assertThat(deep.changeTo("Library", "Albums")).isEqualTo(StackAnimation.None)
        assertThat(discarded).containsExactly("1:Folders", "2:Rock")
        assertThat(deep.pair).isNull()
    }

    @Test
    fun `popping several screens back to the root runs one pop from the top screen to the root`() = runTest {
        val motion = StackMotion(listOf("Library", "Albums", "Abbey Road", "Settings"))

        assertThat(motion.changeTo("Library")).isEqualTo(StackAnimation.Pop)

        // As on iOS: the screen on top slides off over the root, the screens between never show.
        assertThat(motion.onStage).containsExactly("Library", "Settings").inOrder()
        assertThat(motion.progress.value).isEqualTo(0f)
        assertThat(discarded).containsExactly("1:Albums", "2:Abbey Road", "3:Settings")
        motion.settle(listOf("Library"))
        assertThat(motion.onStage).containsExactly("Library")

        val reduced = StackMotion(listOf("Library", "Albums", "Abbey Road"))
        assertThat(reduced.changeTo("Library", reduced = true)).isEqualTo(StackAnimation.None)
    }

    @Test
    fun `a push gesture stages the new screen over the current one with its future key`() = runTest {
        val motion = StackMotion(listOf("Library"))

        assertThat(motion.pushMoved(listOf("Library"), "Albums", 0.3f)).isTrue()

        assertThat(motion.onStage).containsExactly("Library", "Albums").inOrder()
        assertThat(motion.pair?.edge).isEqualTo(SwipeEdge.None)
        assertThat(motion.pair?.top?.stateKey).isEqualTo("1:Albums") // the key the real push gives it
        assertThat(motion.progress.value).isWithin(1e-6f).of(0.7f)
        assertThat(motion.shown).containsExactly("Library") // nothing is pushed yet
        assertThat(stackFrame(motion.progress.value, SwipeEdge.None).topOffset).isWithin(1e-6f).of(0.7f)
        assertThat(motion.isOnStack("1:Albums")).isFalse()
    }

    @Test
    fun `a running push gesture can keep following the finger`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.pushMoved(listOf("Library"), "Albums", 0.2f)

        assertThat(motion.pushMoved(listOf("Library"), "Albums", 0.5f)).isTrue()

        assertThat(motion.progress.value).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `a committed push gesture pushes without animating the push a second time`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.pushMoved(listOf("Library"), "Albums", 0.6f)
        motion.progress.snapTo(0f) // the composable finishes the motion
        motion.gestureFinished()

        assertThat(motion.changeTo("Library", "Albums")).isEqualTo(StackAnimation.None)
        assertThat(discarded).isEmpty()
        motion.settle(listOf("Library", "Albums"))

        assertThat(motion.layers.map { it.stateKey }).containsExactly("1:Albums")
        assertThat(motion.isOnStack("1:Albums")).isTrue()
        // Only that one push skips its animation.
        assertThat(motion.changeTo("Library")).isEqualTo(StackAnimation.Pop)
    }

    @Test
    fun `a cancelled push gesture settles on the unchanged stack`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.pushMoved(listOf("Library"), "Albums", 0.4f)

        motion.progress.snapTo(1f) // the composable's spring back
        motion.settle(listOf("Library"))

        assertThat(motion.onStage).containsExactly("Library")
        assertThat(motion.shown).containsExactly("Library")
        assertThat(discarded).isEmpty()
        assertThat(motion.isOnStack("1:Albums")).isFalse()
        assertThat(motion.changeTo("Library", "Albums")).isEqualTo(StackAnimation.Push)
    }

    @Test
    fun `a push gesture never takes over a running transition`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.changeTo("Library", "Albums") // push running

        assertThat(motion.pushMoved(listOf("Library", "Albums"), "Songs", 0.3f)).isFalse()
        assertThat(motion.pair?.top?.stateKey).isEqualTo("1:Albums")
        assertThat(motion.progress.value).isEqualTo(1f)

        // A stack the motion has not shown yet is never staged on.
        val fresh = StackMotion(listOf("Library"))
        assertThat(fresh.pushMoved(listOf("Library", "Songs"), "Rock", 0.3f)).isFalse()
        assertThat(fresh.pair).isNull()
    }

    @Test
    fun `a gesture that changed nothing does not swallow the next animation`() = runTest {
        val motion = StackMotion(listOf("Library"))
        motion.gestureFinished()
        motion.settle(listOf("Library"))

        assertThat(motion.changeTo("Library", "Albums")).isEqualTo(StackAnimation.Push)
    }

    @Test
    fun `the Now Playing sheet keeps a committed frame for its exit and comes back full size`() = runTest {
        val sheet = SheetBackMotion()
        sheet.moved(0.8f)
        assertThat(sheet.frame).isEqualTo(sheetFrame(0.8f))

        // Committed: the sheet is dismissed from the shrunken frame, nothing resets it.
        assertThat(sheet.frame.scale).isLessThan(1f)

        sheet.reshown()
        assertThat(sheet.frame).isEqualTo(sheetFrame(0f))
        assertThat(sheet.frame.scale).isEqualTo(1f)
    }
}
