package io.sonora.core.designsystem.motion

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** The Now Playing sheet's shared open progress: settle rules and the drag/release state machine. */
class SheetPresentationTest {

    private val height = 2_000f
    private val distance = 400f

    private val fling = 1_000f

    private fun settlesOpen(
        startedOpen: Boolean,
        progress: Float,
        velocity: Float = 0f,
        startProgress: Float = if (startedOpen) 1f else 0f,
    ) = sheetSettlesOpen(startedOpen, startProgress, progress, height, velocity, distance, fling)

    @Test
    fun `from closed a short pull returns and a pull past the distance opens`() {
        assertThat(settlesOpen(startedOpen = false, progress = 0.1f)).isFalse() // 200 px
        assertThat(settlesOpen(startedOpen = false, progress = 0.25f)).isTrue() // 500 px
    }

    @Test
    fun `from open a short drag down stays and a drag past the distance dismisses`() {
        assertThat(settlesOpen(startedOpen = true, progress = 0.9f)).isTrue() // 200 px down
        assertThat(settlesOpen(startedOpen = true, progress = 0.75f)).isFalse() // 500 px down
    }

    @Test
    fun `a fling decides over distance in both directions`() {
        assertThat(settlesOpen(startedOpen = false, progress = 0.05f, velocity = -fling)).isTrue()
        assertThat(settlesOpen(startedOpen = false, progress = 0.6f, velocity = fling)).isFalse()
        assertThat(settlesOpen(startedOpen = true, progress = 0.95f, velocity = fling)).isFalse()
        assertThat(settlesOpen(startedOpen = true, progress = 0.4f, velocity = -fling)).isTrue()
    }

    @Test
    fun `a sheet grabbed while opening and let go in place keeps opening`() {
        // Emulator: a touch that caught the sheet mid-spring at 0.4 and lifted closed it (travel measured from "open").
        assertThat(settlesOpen(startedOpen = true, startProgress = 0.4f, progress = 0.42f)).isTrue()
        assertThat(settlesOpen(startedOpen = true, startProgress = 0.4f, progress = 0.15f)).isFalse() // 500 px down
    }

    @Test
    fun `a sheet grabbed while closing and let go in place keeps closing`() {
        assertThat(settlesOpen(startedOpen = false, startProgress = 0.6f, progress = 0.58f)).isFalse()
        assertThat(settlesOpen(startedOpen = false, startProgress = 0.6f, progress = 0.85f)).isTrue() // 500 px up
    }

    @Test
    fun `a slow release goes by distance whatever its direction`() {
        assertThat(settlesOpen(startedOpen = false, progress = 0.5f, velocity = 300f)).isTrue()
        assertThat(settlesOpen(startedOpen = true, progress = 0.5f, velocity = -300f)).isFalse()
    }

    @Test
    fun `the sheet frame follows progress like the drag-down did`() {
        val open = sheetPresentFrame(1f)
        assertThat(open.offsetFraction).isEqualTo(0f)
        assertThat(open.scale).isEqualTo(1f)
        val pulled = sheetPresentFrame(0.9f)
        assertThat(pulled.scale).isWithin(1e-6f).of(1f - 0.1f / 4)
        val closed = sheetPresentFrame(0f)
        assertThat(closed.scale).isWithin(1e-6f).of(1f - SHEET_DRAG_MAX_SHRINK)
        assertThat(sheetPresentFrame(1.2f)).isEqualTo(open)
    }

    /** Top edge of the scaled (centre pivot) sheet, as a share of its height. */
    private fun SheetPresentFrame.topEdge() = offsetFraction + (1f - scale) / 2f

    @Test
    fun `the top edge of the sheet moves one to one with progress`() {
        // Emulator: finger 307 px up, sheet top only ~180 px up: the centred shrink pushed the top edge down.
        for (p in listOf(0f, 0.05f, 0.128f, 0.3f, 0.9f, 1f)) {
            assertThat(sheetPresentFrame(p).topEdge()).isWithin(1e-6f).of(1f - p)
        }
    }

    private fun TestScope.sheet(open: Boolean = false) =
        SheetPresentation(open, this) { true }.apply {
            heightPx = height
            settleDistancePx = distance
            flingVelocityPx = fling
        }

    @Test
    fun `dragging the closed sheet up follows the finger one to one`() = runTest {
        val sheet = sheet()
        assertThat(sheet.shown).isFalse()

        sheet.dragStarted()
        sheet.dragBy(-300f)
        sheet.dragBy(-100f)
        advanceUntilIdle()

        assertThat(sheet.progress.value).isWithin(1e-6f).of(0.2f)
        assertThat(sheet.shown).isTrue()
        assertThat(sheet.covers).isFalse()
        sheet.dragBy(200f) // back down: the finger moves freely
        advanceUntilIdle()
        assertThat(sheet.progress.value).isWithin(1e-6f).of(0.1f)
    }

    @Test
    fun `releasing past the distance opens and the sheet then covers the shell`() = runTest {
        val sheet = sheet()
        sheet.dragStarted()
        sheet.dragBy(-500f)
        sheet.dragStopped(velocityPx = 0f)
        advanceUntilIdle()

        assertThat(sheet.targetOpen).isTrue()
        assertThat(sheet.progress.value).isEqualTo(1f)
        assertThat(sheet.covers).isTrue()
    }

    @Test
    fun `a small pull springs back closed and hides the sheet`() = runTest {
        val sheet = sheet()
        sheet.dragStarted()
        sheet.dragBy(-100f)
        sheet.dragStopped(velocityPx = 0f)
        advanceUntilIdle()

        assertThat(sheet.targetOpen).isFalse()
        assertThat(sheet.progress.value).isEqualTo(0f)
        assertThat(sheet.shown).isFalse()
    }

    @Test
    fun `dragging an open sheet down past the distance dismisses it`() = runTest {
        val sheet = sheet(open = true)
        assertThat(sheet.covers).isTrue()

        sheet.dragStarted()
        sheet.dragBy(500f)
        advanceUntilIdle()
        assertThat(sheet.covers).isFalse() // the shell shows again under a moving sheet
        sheet.dragStopped(velocityPx = 0f)
        advanceUntilIdle()

        assertThat(sheet.targetOpen).isFalse()
        assertThat(sheet.progress.value).isEqualTo(0f)
    }

    @Test
    fun `progress stays within closed and open`() = runTest {
        val sheet = sheet(open = true)
        sheet.dragStarted()
        sheet.dragBy(-400f)
        advanceUntilIdle()
        assertThat(sheet.progress.value).isEqualTo(1f)
        sheet.dragBy(5_000f)
        advanceUntilIdle()
        assertThat(sheet.progress.value).isEqualTo(0f)
    }

    @Test
    fun `open and close settle without a drag and closeNow hides at once`() = runTest {
        val sheet = sheet()
        sheet.open()
        advanceUntilIdle()
        assertThat(sheet.covers).isTrue()

        sheet.close()
        advanceUntilIdle()
        assertThat(sheet.shown).isFalse()

        sheet.open()
        advanceUntilIdle()
        sheet.closeNow()
        assertThat(sheet.targetOpen).isFalse()
        advanceUntilIdle()
        assertThat(sheet.progress.value).isEqualTo(0f)
    }

    @Test
    fun `a drag on an open sheet before its height is measured keeps it open`() = runTest {
        val sheet = sheet(open = true).apply { heightPx = 0f }

        sheet.dragStarted()
        sheet.dragBy(300f) // ignored: no height to measure progress against
        sheet.dragStopped(velocityPx = 0f)
        advanceUntilIdle()

        assertThat(sheet.targetOpen).isTrue()
        assertThat(sheet.progress.value).isEqualTo(1f)
    }

    @Test
    fun `a drag on a closed sheet before its height is measured keeps it closed`() = runTest {
        val sheet = sheet().apply { heightPx = 0f }

        sheet.dragStarted()
        sheet.dragStopped(velocityPx = -5_000f)
        advanceUntilIdle()

        assertThat(sheet.targetOpen).isFalse()
    }

    @Test
    fun `a quick flick up from the mini player opens`() = runTest {
        // Emulator: 157 px in 80 ms (about 1 960 px/s) did not open with the old fixed 2 000 px/s threshold.
        val sheet = sheet()
        sheet.dragStarted()
        sheet.dragBy(-136f)
        sheet.dragStopped(velocityPx = -1_200f)
        advanceUntilIdle()
        assertThat(sheet.targetOpen).isTrue()
    }

    @Test
    fun `the shell stays while the finger holds the sheet fully up`() = runTest {
        val sheet = sheet()
        sheet.dragStarted()
        sheet.dragBy(-3_000f)
        advanceUntilIdle()
        assertThat(sheet.progress.value).isEqualTo(1f)
        assertThat(sheet.covers).isFalse()
        sheet.dragStopped(velocityPx = 0f)
        advanceUntilIdle()
        assertThat(sheet.covers).isTrue()
    }

    @Test
    fun `a cancelled drag past the distance opens and a short one returns`() = runTest {
        val far = sheet()
        far.dragStarted()
        far.dragBy(-500f)
        far.dragCancelled()
        advanceUntilIdle()
        assertThat(far.targetOpen).isTrue()

        val short = sheet()
        short.dragStarted()
        short.dragBy(-100f)
        short.dragCancelled()
        advanceUntilIdle()
        assertThat(short.targetOpen).isFalse()
        assertThat(short.dragging).isFalse()
    }

    @Test
    fun `without a measured height drags are ignored`() = runTest {
        val sheet = SheetPresentation(false, this) { true }
        sheet.dragStarted()
        sheet.dragBy(-500f)
        sheet.dragStopped(0f)
        advanceUntilIdle()
        assertThat(sheet.progress.value).isEqualTo(0f)
        assertThat(sheet.targetOpen).isFalse()
    }

    private class NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /**
     * A composition reading [shown] and [covers] is recomposed when their value changes, not on every step of the
     * progress: the shell reads both, and a read that followed the progress itself recomposed all of it on every frame
     * of Now Playing's opening and of a drag (T10).
     */
    @Test
    fun `a composition reading shown and covers recomposes when their value changes, not on every step of the progress`() = runTest {
        val sheet = sheet()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val running = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoApplier(), recomposer)
        var compositions = 0
        composition.setContent {
            compositions++
            sheet.shown
            sheet.covers
        }
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            advanceUntilIdle()
            clock.sendFrame(0L)
            advanceUntilIdle()
        }
        try {
            advanceUntilIdle()
            assertThat(compositions).isEqualTo(1)

            sheet.dragStarted()
            // The first pixel shows the sheet: one recomposition.
            sheet.dragBy(-10f)
            advanceUntilIdle()
            frame()
            assertThat(compositions).isEqualTo(2)

            // Pulled up through most of its way: the progress moves every frame, neither value does.
            for (step in 1..8) {
                sheet.dragBy(-150f)
                advanceUntilIdle()
                frame()
            }
            assertThat(sheet.progress.value).isGreaterThan(0.5f)
            assertThat(compositions).isEqualTo(2)

            // Settling open makes it cover the shell: the next recomposition.
            sheet.dragStopped(velocityPx = 0f)
            advanceUntilIdle()
            frame()
            assertThat(sheet.covers).isTrue()
            assertThat(compositions).isAtMost(4)
            assertThat(compositions).isAtLeast(3)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.cancelAndJoin()
        }
    }

    @Test
    fun `opening and closing settle on one spring, without overshoot or a snap at the end`() {
        // T67.8: the bubble's opening and dismissal are mirror images, and with a size-linear morph the spring's last
        // step would show as a jump of the card and its artwork.
        assertThat(sheetSettleSpring(open = true)).isEqualTo(sheetSettleSpring(open = false))
        assertThat(sheetSettleSpring(open = true).dampingRatio).isAtLeast(1f)
        assertThat(sheetSettleSpring(open = true).visibilityThreshold).isNotNull()
        assertThat(sheetSettleSpring(open = true).visibilityThreshold!!).isAtMost(0.001f)
    }
}
