package io.sonora.core.watchphone.remote

import android.os.SystemClock
import android.util.Log
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.watchphone.di.RemoteScope
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.WatchSyncPaths
import io.sonora.core.watchsync.remote.RemoteCodec
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.RemoteLink
import io.sonora.core.watchsync.remote.RemoteSubscriptions
import io.sonora.core.watchsync.remote.TrustedNodes
import io.sonora.core.watchsync.remote.VolumeStepLimiter
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's end of the remote. [RemoteListenerService] hands it every message that arrives; it checks the sender is one of
 * the user's watches, then either keeps a subscription (pushing the playback state to that watch whenever it changes) or
 * runs the command through [RemoteCommandExecutor]. Messages are handled one at a time, in order. The player is looked up
 * only once a trusted watch sends something, so a stranger's message cannot even start the playback service.
 */
@Singleton
class RemoteHost internal constructor(
    private val controller: () -> PlaybackController,
    private val volume: RemoteVolume,
    private val link: RemoteLink,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        controller: Provider<PlaybackController>,
        volume: RemoteVolume,
        link: RemoteLink,
        @RemoteScope scope: CoroutineScope,
    ) : this({ controller.get() }, volume, link, scope, SystemClock::elapsedRealtime)

    private val trusted = TrustedNodes(
        lookup = { link.nodes(WatchSyncPaths.WATCH_CAPABILITY).map { it.id }.toSet() },
        now = now,
    )
    private val executor by lazy { RemoteCommandExecutor(controller(), volume, VolumeStepLimiter(now = now)) }
    private val subscriptions = RemoteSubscriptions()
    private val lock = Mutex()
    private var watching: Job? = null

    /** A flood is bounded: past this many waiting messages the oldest are dropped. */
    private val inbox = Channel<Pair<String, ByteArray>>(INBOX_SIZE, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch { for ((nodeId, bytes) in inbox) handle(nodeId, bytes) }
    }

    /** Queues a message for [handle]; safe to call from any thread. */
    fun onMessage(nodeId: String, bytes: ByteArray) {
        inbox.trySend(nodeId to bytes)
    }

    internal suspend fun handle(nodeId: String, bytes: ByteArray) {
        val command = try {
            RemoteCodec.decodeCommand(bytes)
        } catch (e: ProtocolException) {
            return
        }
        if (!trusted.isTrusted(nodeId)) return
        try {
            when (command) {
                // Subscribing and the push it is owed happen under one lock, so no other push slips in between.
                is RemoteCommand.Subscribe -> lock.withLock {
                    subscriptions.subscribe(nodeId, command.withQueue, now())
                    watchState()
                    pushDueLocked()
                }
                RemoteCommand.Unsubscribe -> lock.withLock { subscriptions.unsubscribe(nodeId) }
                else -> {
                    val result = executor.execute(command)
                    if (result is CommandResult.Rejected) Log.d(TAG, "Rejected $command: ${result.reason}")
                    pushDue()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote command failed", e)
        }
    }

    /** Follows the player while somebody is subscribed; stops by itself once every lease has lapsed. */
    private fun watchState() {
        if (watching?.isActive == true) return
        val player = controller()
        watching = scope.launch {
            player.state.onEach { pushDue() }.launchIn(this)
            while (isActive) {
                delay(LEASE_CHECK_MS)
                val alive = lock.withLock { subscriptions.hasSubscribers(now()) }
                if (!alive) cancel()
            }
        }
    }

    /** Sends each subscriber the picture of the player if it changed; one send at a time keeps them in order. */
    private suspend fun pushDue() = lock.withLock { pushDueLocked() }

    private suspend fun pushDueLocked() {
        val pushes = subscriptions.due(now()) { withQueue ->
            remoteState(controller().state.value, volume.level(), withQueue)
        }
        for (push in pushes) {
            val sent = try {
                withTimeoutOrNull(SEND_TIMEOUT_MS) { link.send(push.nodeId, RemoteCodec.encodeState(push.state)) } != null
            } catch (e: IOException) {
                false
            }
            if (!sent) subscriptions.markUnsent(push.nodeId)
        }
    }

    private companion object {
        const val TAG = "SonoraRemote"
        const val INBOX_SIZE = 64
        const val LEASE_CHECK_MS = 10_000L
        const val SEND_TIMEOUT_MS = 5_000L
    }
}
