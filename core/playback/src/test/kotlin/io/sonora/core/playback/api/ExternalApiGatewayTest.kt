package io.sonora.core.playback.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiMethod
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.ApiResult
import io.sonora.core.api.Page
import io.sonora.core.api.TrackRow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Test

/**
 * The time and thread rules of the provider: the work runs on the worker thread, a binder thread waits for it only
 * for a bounded time, and a player that is not connected is reported instead of acted on.
 */
class ExternalApiGatewayTest {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "api-worker") }
    private val connected = MutableStateFlow(true)
    private val seen = mutableListOf<String>()
    private val ranOn = AtomicReference<String>()
    private var switch: suspend () -> Boolean = { true }
    private var onCall: suspend (String, Map<String, Any?>) -> ApiResult = { method, _ ->
        ranOn.set(Thread.currentThread().name)
        seen += method
        ApiResult.Ok(mapOf("count" to 1))
    }
    private var onQuery: suspend (List<String>, Map<String, Any?>) -> ApiQueryResult = { segments, _ ->
        ranOn.set(Thread.currentThread().name)
        seen += segments.joinToString("/")
        ApiQueryResult.Tracks(Page(emptyList<TrackRow>(), 0, 50, 0))
    }

    private fun gateway(
        callBudgetMs: Long = 2_000,
        connectWaitMs: Long = 200,
        switchBudgetMs: Long = 200,
        playbackConnected: Flow<Boolean> = connected,
    ) = ExternalApiGateway(
        switchOn = { switch() },
        playbackConnected = playbackConnected,
        runCall = { method, extras -> onCall(method, extras) },
        runQuery = { segments, params -> onQuery(segments, params) },
        workDispatcher = worker.asCoroutineDispatcher(),
        limits = GatewayLimits(callBudgetMs, connectWaitMs, switchBudgetMs),
    )

    @After
    fun tearDown() = worker.shutdownNow().let { }

    @Test
    fun `a call runs on the worker thread and returns its result`() {
        val result = gateway().call("play", mapOf("a" to 1))

        assertThat(result).isEqualTo(ApiResult.Ok(mapOf("count" to 1)))
        assertThat(ranOn.get()).startsWith("api-worker")
        assertThat(seen).containsExactly("play")
    }

    @Test
    fun `a query runs on the worker thread and returns its result`() {
        val result = gateway().query(listOf("tracks"), emptyMap())

        assertThat(result).isInstanceOf(ApiQueryResult.Tracks::class.java)
        assertThat(ranOn.get()).startsWith("api-worker")
    }

    @Test
    fun `a call that outlasts its budget comes back as a timeout and is cancelled`() {
        val cancelled = AtomicBoolean(false)
        onCall = { _, _ ->
            try {
                delay(10_000)
                ApiResult.Ok()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw e
            }
        }
        val started = System.nanoTime()

        val result = gateway(callBudgetMs = 150).call("play", emptyMap())

        assertThat(result).isEqualTo(ApiResult.Failed(ApiError.Timeout))
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
        Thread.sleep(100)
        assertThat(cancelled.get()).isTrue()
    }

    @Test
    fun `a query that outlasts its budget comes back as a timeout`() {
        onQuery = { _, _ -> delay(10_000); error("unreachable") }

        val result = gateway(callBudgetMs = 150).query(listOf("tracks"), emptyMap())

        assertThat(result).isEqualTo(ApiQueryResult.Failed(ApiError.Timeout))
    }

    @Test
    fun `a playback command waits for the player to connect`() {
        connected.value = false
        CoroutineScope(Dispatchers.Default).launch {
            delay(60)
            connected.value = true
        }

        val result = gateway(connectWaitMs = 1_000).call("next", emptyMap())

        assertThat(result).isInstanceOf(ApiResult.Ok::class.java)
        assertThat(seen).containsExactly("next")
    }

    @Test
    fun `a playback command is refused, untouched, when the player never connects`() {
        connected.value = false

        for (method in listOf("play", "toggle", "seek", "queue_add", "queue_remove", "play_album", "play_search", "play_tracks")) {
            assertThat(gateway().call(method, emptyMap())).isEqualTo(ApiResult.Failed(ApiError.PlaybackUnavailable))
        }
        assertThat(seen).isEmpty()
    }

    @Test
    fun `the settings that do not need the player run while it is not connected`() {
        connected.value = false

        for (method in listOf("crossfade", "speed", "dj_mode", "sleep_start", "sleep_extend", "sleep_cancel")) {
            assertThat(gateway().call(method, emptyMap())).isInstanceOf(ApiResult.Ok::class.java)
        }
        assertThat(seen).hasSize(6)
    }

    @Test
    fun `an unknown method is passed on so the core can name the error`() {
        connected.value = false

        gateway().call("fly", emptyMap())

        assertThat(seen).containsExactly("fly")
    }

    @Test
    fun `exactly the methods that act on the player wait for it`() {
        val waiting = ApiMethod.entries.filter { ExternalApiGateway.needsPlayer(it.wireName) }.map { it.wireName }

        assertThat(waiting).containsExactly(
            "play", "pause", "toggle", "next", "previous", "seek", "shuffle", "repeat", "queue_add", "queue_remove",
            "queue_move", "queue_clear_upcoming", "queue_skip_to", "play_album", "play_artist", "play_playlist",
            "play_search", "play_tracks",
        )
    }

    @Test
    fun `the state and the queue wait for the player, the library lists do not`() {
        connected.value = false

        assertThat(gateway().query(listOf("state"), emptyMap())).isEqualTo(ApiQueryResult.Failed(ApiError.PlaybackUnavailable))
        assertThat(gateway().query(listOf("queue"), emptyMap())).isEqualTo(ApiQueryResult.Failed(ApiError.PlaybackUnavailable))
        for (path in listOf("tracks", "albums", "artists", "playlists")) {
            assertThat(gateway().query(listOf(path), emptyMap())).isInstanceOf(ApiQueryResult.Tracks::class.java)
        }
        assertThat(seen).containsExactly("tracks", "albums", "artists", "playlists")
    }

    @Test
    fun `the switch reads on the calling thread`() {
        switch = { true }
        assertThat(gateway().isSwitchOn()).isTrue()

        switch = { false }
        assertThat(gateway().isSwitchOn()).isFalse()
    }

    @Test
    fun `a switch that cannot be read counts as off`() {
        switch = { error("store unreadable") }
        assertThat(gateway().isSwitchOn()).isFalse()

        switch = { delay(10_000); true }
        assertThat(gateway(switchBudgetMs = 100).isSwitchOn()).isFalse()
    }

    @Test
    fun `a flow that ends without connecting counts as not connected`() {
        val result = gateway(playbackConnected = flow { emit(false) }).call("play", emptyMap())

        assertThat(result).isEqualTo(ApiResult.Failed(ApiError.PlaybackUnavailable))
    }
}
