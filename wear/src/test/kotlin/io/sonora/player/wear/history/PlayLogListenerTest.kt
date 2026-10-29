package io.sonora.player.wear.history

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayLogListenerTest {
    private var playerThreadId = -1L
    private val playerThread = Executors.newSingleThreadExecutor { Thread(it, "player-thread").also { t -> playerThreadId = t.id } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val threads = CopyOnWriteArrayList<Long>()

    private val log = object : PlayLog {
        override fun record(entry: PlayLogEntry) = Unit
        override fun between(fromMs: Long, toMs: Long): List<PlayLogEntry> = emptyList()
    }

    private fun onPlayerThread(block: () -> Unit) = playerThread.submit(block).get(5, TimeUnit.SECONDS)

    @Test
    fun `every recorder call, ticks included, runs on the player's thread`() {
        val listener = PlayLogListener(
            log = log,
            scope = scope,
            io = Dispatchers.Default,
            main = playerThread.asCoroutineDispatcher(),
            tickMs = 5,
            wallClock = { 0L },
            monotonic = { threads += Thread.currentThread().id; 0L },
        )

        onPlayerThread { listener.onIsPlayingChanged(true) }
        assertThat(playerThreadId).isNotEqualTo(-1L)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (threads.size < 4 && System.nanoTime() < deadline) Thread.sleep(5)
        onPlayerThread { listener.onIsPlayingChanged(false) }
        onPlayerThread { listener.onPlaybackStateChanged(Player.STATE_ENDED) }

        assertThat(threads.size).isAtLeast(4) // the start plus several ticks
        assertThat(threads.toSet()).containsExactly(playerThreadId)
        scope.cancel()
        playerThread.shutdownNow()
    }
}
