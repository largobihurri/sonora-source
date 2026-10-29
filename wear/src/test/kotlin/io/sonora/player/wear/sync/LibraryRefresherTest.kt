package io.sonora.player.wear.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryRefresherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshes = AtomicInteger()
    private val running = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `a change is refreshed once and nothing is refreshed without one`() = run {
        val refresher = LibraryRefresher { refreshes.incrementAndGet() }

        refresher.refreshIfChanged()
        assertThat(refreshes.get()).isEqualTo(0)

        refresher.changed()
        refresher.changed()
        refresher.refreshIfChanged()
        refresher.refreshIfChanged()

        assertThat(refreshes.get()).isEqualTo(1)
    }

    @Test
    fun `refreshes never overlap and a change made during one is covered by the next`() = run {
        val gate = CompletableDeferred<Unit>()
        val refresher = LibraryRefresher {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            refreshes.incrementAndGet()
            gate.await()
            running.decrementAndGet()
        }
        refresher.changed()
        val first = scope.launch { refresher.refreshIfChanged() }
        while (running.get() == 0) Thread.sleep(5)

        // A second session changes the folder and ends while the first scan is still running.
        refresher.changed()
        val second = scope.launch { refresher.refreshIfChanged() }
        Thread.sleep(150)
        gate.complete(Unit)
        first.join()
        second.join()

        assertThat(mostAtOnce.get()).isEqualTo(1)
        assertThat(refreshes.get()).isEqualTo(2)
    }

    @Test
    fun `a refresh that failed is tried again at the next session end`() = run {
        val refresher = LibraryRefresher {
            if (refreshes.incrementAndGet() == 1) throw IOException("storage busy")
        }
        refresher.changed()

        assertThrows(IOException::class.java) { runBlocking { refresher.refreshIfChanged() } }
        refresher.refreshIfChanged()
        refresher.refreshIfChanged()

        assertThat(refreshes.get()).isEqualTo(2)
    }
}
