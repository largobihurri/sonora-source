package io.sonora.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connection state machine for a session controller, kept free of Media3 so it is JVM-testable.
 *
 * [execute] runs a command on the live controller right away, connecting first if needed. Command policy while
 * disconnected: every command waits for the one shared connection round in flight (never a round of its own).
 * When the round connects, the waiting commands run immediately in issue order; when every attempt fails (the
 * first one plus a retry after each of [retryDelaysMs]) they are all dropped and reported through
 * [onCommandsDropped]. So a command runs within one round of being issued or not at all, never seconds later
 * out of order; the next command after a failed round starts a fresh round. A round that ends abnormally (an
 * [Error], cancellation of [scope], or a cancelled connect future) drops and reports its commands the same way
 * before the failure propagates, so they can never replay in a later round.
 *
 * A new controller goes through [onConnected] before it becomes current; if that throws, it is released through
 * [onDropped] and the attempt counts as failed. [invalidate] (or a controller that is no longer alive) drops the
 * current controller exactly once through [onDropped]. Confined to one thread (the main thread in production);
 * rounds run in [scope].
 */
internal class ControllerConnection<T : Any>(
    private val scope: CoroutineScope,
    private val connect: suspend () -> T,
    private val isAlive: (T) -> Boolean,
    private val onDropped: (T) -> Unit,
    private val onConnected: (T) -> Unit = {},
    private val retryDelaysMs: List<Long> = listOf(500L, 2_000L),
    private val onError: (Throwable) -> Unit = {},
    private val onCommandsDropped: (count: Int) -> Unit = {},
) {
    private var current: T? = null
    private val waiting = ArrayList<(T) -> Unit>()
    private var round: Job? = null

    /** Runs [command] on the live controller now, or after the shared connection round (dropped if it fails). */
    fun execute(command: (T) -> Unit) {
        current?.let { if (isAlive(it)) return command(it) else invalidate(it) }
        waiting += command
        // isActive rather than null: with an immediate dispatcher the round may already have finished inline.
        if (round?.isActive != true) round = scope.launch { runRound() }
    }

    /** Drops [controller] if it is still the current one, so the next [execute] reconnects. */
    fun invalidate(controller: T) {
        if (current !== controller) return
        current = null
        onDropped(controller)
    }

    private suspend fun runRound() {
        val controller = try {
            connectWithRetry()
        } catch (e: Throwable) {
            // Includes java.util.concurrent.CancellationException (the kotlinx alias) from a cancelled future.
            finishRound(null)
            throw e
        }
        finishRound(controller)
    }

    private suspend fun connectWithRetry(): T? {
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) delay(retryDelaysMs[attempt - 1])
            try {
                return connect().also(::setUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
        return null
    }

    /** Hands a new controller to [onConnected], releasing it if that fails so it is never leaked. */
    private fun setUp(controller: T) {
        try {
            onConnected(controller)
        } catch (e: Throwable) {
            onDropped(controller)
            throw e
        }
    }

    private fun finishRound(controller: T?) {
        current = controller
        val commands = waiting.toList()
        waiting.clear()
        if (controller == null) onCommandsDropped(commands.size) else commands.forEach { it(controller) }
    }
}
