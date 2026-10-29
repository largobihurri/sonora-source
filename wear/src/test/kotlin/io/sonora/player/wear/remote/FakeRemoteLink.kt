package io.sonora.player.wear.remote

import io.sonora.core.watchsync.WatchNode
import io.sonora.core.watchsync.remote.RemoteCodec
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.RemoteLink
import io.sonora.core.watchsync.remote.RemoteState
import java.io.IOException

class FakeRemoteLink : RemoteLink {
    var phones: List<WatchNode> = listOf(WatchNode("phone", "Phone", isNearby = true))
    var failLookup = false
    var failSends = false
    val sent = mutableListOf<Pair<String, RemoteCommand>>()
    var listener: ((String, ByteArray) -> Unit)? = null
    var listeners = 0

    val commands: List<RemoteCommand> get() = sent.map { it.second }

    override suspend fun nodes(capability: String): List<WatchNode> {
        if (failLookup) throw IOException("Data Layer gone")
        return phones
    }

    override suspend fun send(nodeId: String, bytes: ByteArray) {
        if (failSends) throw IOException("phone gone")
        sent += nodeId to RemoteCodec.decodeCommand(bytes)
    }

    override fun listen(onMessage: (nodeId: String, bytes: ByteArray) -> Unit): AutoCloseable {
        listener = onMessage
        listeners++
        return AutoCloseable {
            listener = null
            listeners--
        }
    }

    fun deliver(state: RemoteState, from: String = "phone") = listener?.invoke(from, RemoteCodec.encodeState(state))
}
