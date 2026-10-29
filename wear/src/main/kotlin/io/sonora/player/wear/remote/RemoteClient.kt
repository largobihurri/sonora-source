package io.sonora.player.wear.remote

import android.os.SystemClock
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.RemoteCodec
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteLink
import io.sonora.core.watchsync.remote.RemotePaths
import io.sonora.core.watchsync.WatchNodes
import io.sonora.player.wear.playback.QueueEntry
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The watch's end of the remote. While a remote screen is showing ([setMode]) it finds the phone (the node that declares the
 * Sonora phone capability), subscribes to its playback state and renews that every [RemoteLimits.RENEW_MS]; it shows the
 * state, or why there is none, as [state]. Commands are fire and forget: the phone's next state is the answer. Main thread
 * only (give it a main-thread [scope]); the Data Layer calls its listener there.
 */
class RemoteClient(
    private val link: RemoteLink,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val tracker = RemoteConnectionTracker()
    private val coalescer = VolumeStepCoalescer()
    private val mutableState = MutableStateFlow<RemoteUiState>(RemoteUiState.NoPhone)
    private var mode = RemoteMode.Off
    private var phone: String? = null
    private var loop: Job? = null
    private var volumeFlush: Job? = null
    private var listening: AutoCloseable? = null
    private var lastRenewAt = 0L

    /** The phone's last music volume step; what the crown moves from until the phone says otherwise. */
    private var volumeEstimate = 0

    val state: StateFlow<RemoteUiState> = mutableState.asStateFlow()

    fun setMode(newMode: RemoteMode) {
        if (newMode == mode) return
        val old = mode
        mode = newMode
        when {
            newMode == RemoteMode.Off -> stop()
            old == RemoteMode.Off -> begin()
            else -> scope.launch { subscribe() } // the queue screen opened or closed: change what is pushed
        }
    }

    /** The playing song's position now: the phone's, moved on by the time since it was taken. */
    fun positionMs(): Long {
        val ready = mutableState.value as? RemoteUiState.Ready ?: return 0L
        return ready.state.positionAt(now() - ready.receivedAt)
    }

    fun setPlaying(playing: Boolean) = command(RemoteCommand.SetPlaying(playing))

    fun next() = command(RemoteCommand.Next)

    fun previous() = command(RemoteCommand.Previous)

    fun jump(entry: QueueEntry) {
        val id = entry.remoteId() ?: return
        command(RemoteCommand.Jump(entry.index, id))
    }

    fun remove(entry: QueueEntry) {
        val id = entry.remoteId() ?: return
        command(RemoteCommand.Remove(entry.index, id))
    }

    fun move(entry: QueueEntry, direction: QueueDirection) {
        val id = entry.remoteId() ?: return
        command(RemoteCommand.Move(entry.index, id, direction))
    }

    /**
     * Turns the phone's volume by the crown's [steps] (negative lowers it): they are sent in few small messages, and nothing
     * else ever changes the volume. Returns the percent the volume should now be, for the ring; 0 and nothing sent while the
     * phone's state has not arrived.
     */
    fun volumeSteps(steps: Int): Int {
        val ready = mutableState.value as? RemoteUiState.Ready ?: return 0
        coalescer.add(steps)
        sendVolume()
        if (coalescer.hasPending && volumeFlush?.isActive != true) {
            volumeFlush = scope.launch {
                while (coalescer.hasPending) {
                    delay(VolumeStepCoalescer.DEFAULT_INTERVAL_MS)
                    sendVolume()
                }
            }
        }
        val estimate = ready.state.copy(volume = volumeEstimate).withVolumeSteps(steps)
        volumeEstimate = estimate.volume
        return estimate.volumePercent
    }

    private fun sendVolume() {
        val steps = coalescer.take(now())
        if (steps != 0) command(RemoteCommand.VolumeSteps(steps))
    }

    private fun begin() {
        tracker.start(now())
        lastRenewAt = 0L
        publish()
        listening = link.listen(::onMessage)
        loop = scope.launch {
            while (isActive) {
                val connected = tracker.ui(now()) is RemoteUiState.Ready
                if (!connected || now() - lastRenewAt >= RemoteLimits.RENEW_MS) renew()
                publish()
                delay(TICK_MS)
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        volumeFlush?.cancel()
        listening?.close()
        loop = null
        volumeFlush = null
        listening = null
        coalescer.clear()
        val gone = phone
        phone = null
        tracker.start(now())
        publish()
        if (gone != null) scope.launch { send(gone, RemoteCommand.Unsubscribe) }
    }

    /** Looks for the phone again and, if it is there, asks for the state (which also renews the lease). */
    private suspend fun renew() {
        lastRenewAt = now()
        val found = try {
            WatchNodes.pick(link.nodes(RemotePaths.PHONE_CAPABILITY))?.id
        } catch (e: IOException) {
            null
        }
        phone = found
        tracker.onPhone(found != null, now())
        if (found != null) subscribe()
    }

    private suspend fun subscribe() {
        val target = phone ?: return
        send(target, RemoteCommand.Subscribe(withQueue = mode == RemoteMode.Queue))
    }

    private fun onMessage(nodeId: String, bytes: ByteArray) {
        if (nodeId != phone) return
        val received = try {
            RemoteCodec.decodeState(bytes)
        } catch (e: ProtocolException) {
            return
        }
        volumeEstimate = received.volume
        tracker.onState(received, now())
        publish()
    }

    private fun command(command: RemoteCommand) {
        val target = phone ?: return
        scope.launch { send(target, command) }
    }

    private suspend fun send(nodeId: String, command: RemoteCommand) {
        try {
            link.send(nodeId, RemoteCodec.encodeCommand(command))
        } catch (e: IOException) {
            // The phone is out of reach; the next renewal finds out and the screen says so.
        }
    }

    private fun publish() {
        mutableState.value = tracker.ui(now())
    }

    private companion object {
        /** How often the screen's state is re-evaluated (staleness) and a missing phone is looked for again. */
        const val TICK_MS = 5_000L
    }
}
