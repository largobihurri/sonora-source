package io.sonora.core.watchlink

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import io.sonora.core.watchsync.WatchNode
import io.sonora.core.watchsync.remote.RemoteLink
import io.sonora.core.watchsync.remote.RemotePaths

/**
 * [RemoteLink] over the Wearable Data Layer: capability lookup, `MessageClient` sends and a listener for the remote's path.
 * Both apps use it; the phone also receives through a manifest listener service, which wakes the app.
 */
class WearableRemoteLink(context: Context) : RemoteLink {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val messages = Wearable.getMessageClient(context)

    override suspend fun nodes(capability: String): List<WatchNode> =
        capabilities.getCapability(capability, CapabilityClient.FILTER_REACHABLE).awaitIo()
            .nodes.map { WatchNode(it.id, it.displayName, it.isNearby) }

    override suspend fun send(nodeId: String, bytes: ByteArray) {
        messages.sendMessage(nodeId, RemotePaths.MESSAGE_PATH, bytes).awaitIo()
    }

    override fun listen(onMessage: (nodeId: String, bytes: ByteArray) -> Unit): AutoCloseable {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == RemotePaths.MESSAGE_PATH) onMessage(event.sourceNodeId, event.data)
        }
        messages.addListener(listener)
        return AutoCloseable { messages.removeListener(listener) }
    }
}
