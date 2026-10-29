package io.sonora.feature.nowplaying

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * A composition with no UI, on the JVM: a [Recomposer] driven by a frame clock the test advances ([frame]), with a
 * resumed lifecycle and a bare [Context] provided (no sensors: `getSystemService` answers null). It runs effects and
 * counts nothing itself: tests count recompositions inside their own content. No nodes: the content must not emit any.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
internal class HeadlessComposition(private val scope: TestScope) {

    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(NoNodes, recomposer)
    private val lifecycle = ResumedLifecycle()
    private var now = 0L

    /** The frames sent so far. */
    var frames = 0
        private set

    init {
        scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent {
            CompositionLocalProvider(LocalContext provides BareContext, LocalLifecycleOwner provides lifecycle, content = content)
        }
        settle()
    }

    /** Hands state changes to the recomposer and runs what is ready, without a frame. */
    fun settle() {
        Snapshot.sendApplyNotifications()
        scope.runCurrent()
    }

    /** One 60 Hz frame: state changes first, then the frame (recomposition, effects awaiting it), then what follows. */
    fun frame() {
        settle()
        now += 16_666_667L
        frames++
        clock.sendFrame(now)
        settle()
    }

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
    }

    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("no nodes")
        override fun insertBottomUp(index: Int, instance: Unit) = error("no nodes")
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** android.jar is stubbed in unit tests: this context has no services. */
    private object BareContext : ContextWrapper(null)

    private class ResumedLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
