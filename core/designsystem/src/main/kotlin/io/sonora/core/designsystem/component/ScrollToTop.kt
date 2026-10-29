package io.sonora.core.designsystem.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.collectLatest

/**
 * Requests to bring a tab's visible list back to its top: the app shell [request]s one when the selected tab is tapped
 * again, as iOS does (Android has no status bar tap). Each tab has its own; screens find theirs in [LocalScrollToTop].
 */
@Stable
class ScrollToTop {
    /** How many requests were made; only new ones scroll (see [ScrollToTopCursor]). */
    var count by mutableIntStateOf(0)
        private set

    fun request() {
        count++
    }
}

/**
 * Which of a [ScrollToTop]'s requests a list has answered. It starts at the count the list first saw, so a list neither
 * scrolls when it appears nor when it recomposes; requests that pile up while it is busy scroll it once.
 */
internal class ScrollToTopCursor(private var answered: Int) {
    /** Whether [count] holds a request not answered yet; it is answered from now on. */
    fun take(count: Int): Boolean {
        if (count == answered) return false
        answered = count
        return true
    }
}

/** The enclosing tab's requests; `null` outside the app shell (previews, tests, screens shown outside a tab). */
val LocalScrollToTop = staticCompositionLocalOf<ScrollToTop?> { null }

/** Animates [state] to its first item when the enclosing tab asks ([LocalScrollToTop]); a finger on the list stops it. */
@Composable
fun ScrollToTopEffect(state: LazyListState) = ScrollToTopEffect(state) { state.animateScrollToItem(0) }

/** Animates [state] to its first item when the enclosing tab asks ([LocalScrollToTop]); a finger on the grid stops it. */
@Composable
fun ScrollToTopEffect(state: LazyGridState) = ScrollToTopEffect(state) { state.animateScrollToItem(0) }

@Composable
private fun ScrollToTopEffect(key: Any, scrollToTop: suspend () -> Unit) {
    val requests = LocalScrollToTop.current ?: return
    val latest by rememberUpdatedState(scrollToTop)
    LaunchedEffect(requests, key) {
        val cursor = ScrollToTopCursor(requests.count)
        // A newer request restarts the animation. Scrolling the state directly dispatches no nested scroll, so the
        // shell's bottom chrome keeps the size the shell gives it.
        snapshotFlow { requests.count }.collectLatest { if (cursor.take(it)) latest() }
    }
}
