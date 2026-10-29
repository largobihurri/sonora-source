package io.sonora.core.playback.api

import android.os.Looper
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiMethod
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** How long the provider lets a binder thread wait, in milliseconds. */
internal data class GatewayLimits(
    /** The most a `call` or `query` may take in all, the wait for the player included. */
    val callBudgetMs: Long = 15_000,
    /** The most a command that acts on the player waits for it to connect (part of [callBudgetMs]). */
    val connectWaitMs: Long = 6_000,
    /** The most the switch may take to read; a switch that cannot be read in time counts as off. */
    val switchBudgetMs: Long = 1_000,
)

/**
 * Runs the core for the provider under the rules a binder thread needs:
 * - the work runs on [workDispatcher] (the main thread in production, which `Media3PlaybackController` needs) while
 *   the calling binder thread waits for it for at most [GatewayLimits.callBudgetMs]; past that the work is cancelled
 *   and the caller gets [ApiError.Timeout] (a command already handed to the player may still take effect);
 * - a method that acts on the player, and the `state` and `queue` reads, first wait up to
 *   [GatewayLimits.connectWaitMs] for the playback service to connect and answer [ApiError.PlaybackUnavailable]
 *   instead of acting on, or reporting, a player that is not there;
 * - the switch is read on the calling thread, bounded, and counts as off when it cannot be read.
 *
 * A caller already on the main thread (Sonora's own uid, or a provider call made in-process) would wait for work that
 * cannot start, holding the thread for the whole budget. So a `call` or `query` made there is not run: it is answered
 * at once with `ApiResult.Failed(ApiError.Failed)` (`failed` on the wire), and nothing reaches the core. The provider
 * is for other processes, whose calls arrive on binder threads; in-process code must call from a background thread.
 */
internal class ExternalApiGateway(
    private val switchOn: suspend () -> Boolean,
    private val playbackConnected: Flow<Boolean>,
    private val runCall: suspend (String, Map<String, Any?>) -> ApiResult,
    private val runQuery: suspend (List<String>, Map<String, Any?>) -> ApiQueryResult,
    private val workDispatcher: CoroutineDispatcher,
    private val limits: GatewayLimits = GatewayLimits(),
) {
    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)

    /** Whether the user turned External control on. Off when it cannot be read in time. */
    fun isSwitchOn(): Boolean = try {
        runBlocking { withTimeoutOrNull(limits.switchBudgetMs) { switchOn() } } ?: false
    } catch (_: Exception) {
        false
    }

    fun call(method: String, extras: Map<String, Any?>): ApiResult =
        if (callerBlocksWork()) ApiResult.Failed(ApiError.Failed) else bounded(ApiResult.Failed(ApiError.Timeout)) {
            if (needsPlayer(method) && !playerConnects()) ApiResult.Failed(ApiError.PlaybackUnavailable) else runCall(method, extras)
        }

    fun query(segments: List<String>, params: Map<String, Any?>): ApiQueryResult =
        if (callerBlocksWork()) ApiQueryResult.Failed(ApiError.Failed) else bounded(ApiQueryResult.Failed(ApiError.Timeout)) {
            if (readsPlayer(segments) && !playerConnects()) {
                ApiQueryResult.Failed(ApiError.PlaybackUnavailable)
            } else {
                runQuery(segments, params)
            }
        }

    /** Whether the calling thread is the one the work needs: only the main thread is, and only when it is the work thread. */
    private fun callerBlocksWork(): Boolean =
        workDispatcher is MainCoroutineDispatcher && Looper.myLooper() === Looper.getMainLooper()

    private suspend fun playerConnects(): Boolean =
        withTimeoutOrNull(limits.connectWaitMs) { playbackConnected.firstOrNull { it } } == true

    private fun <T> bounded(onTimeout: T, block: suspend () -> T): T {
        val work = scope.async { block() }
        return try {
            runBlocking { withTimeout(limits.callBudgetMs) { work.await() } }
        } catch (_: TimeoutCancellationException) {
            work.cancel()
            onTimeout
        }
    }

    companion object {
        /** The methods that act on the player; the others (crossfade, speed, DJ mode, sleep timer) do not need it. */
        private val PLAYER_METHODS = setOf(
            ApiMethod.PLAY, ApiMethod.PAUSE, ApiMethod.TOGGLE, ApiMethod.NEXT, ApiMethod.PREVIOUS, ApiMethod.SEEK,
            ApiMethod.SHUFFLE, ApiMethod.REPEAT, ApiMethod.QUEUE_ADD, ApiMethod.QUEUE_REMOVE, ApiMethod.QUEUE_MOVE,
            ApiMethod.QUEUE_CLEAR_UPCOMING, ApiMethod.QUEUE_SKIP_TO, ApiMethod.PLAY_ALBUM, ApiMethod.PLAY_ARTIST,
            ApiMethod.PLAY_PLAYLIST, ApiMethod.PLAY_SEARCH, ApiMethod.PLAY_TRACKS,
        )

        /** Whether [method] acts on the player. An unknown name does not: the core names the error. */
        fun needsPlayer(method: String): Boolean = ApiMethod.fromWireName(method) in PLAYER_METHODS

        /** `state` and `queue` read what the connected controller reports; the library lists do not. */
        private fun readsPlayer(segments: List<String>) = segments == listOf("state") || segments == listOf("queue")
    }
}
