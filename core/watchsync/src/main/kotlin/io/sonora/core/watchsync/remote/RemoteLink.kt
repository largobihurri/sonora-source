package io.sonora.core.watchsync.remote

import io.sonora.core.watchsync.WatchNode

/**
 * The Data Layer as the remote uses it, so the phone's host and the watch's client can be tested without Play Services.
 * [send] and [nodes] fail with an `IOException` when the Data Layer is unavailable or the node is gone.
 */
interface RemoteLink {
    /** The reachable nodes that declare [capability]. */
    suspend fun nodes(capability: String): List<WatchNode>

    /** Sends one message to [nodeId] on [RemotePaths.MESSAGE_PATH]. */
    suspend fun send(nodeId: String, bytes: ByteArray)

    /** Calls [onMessage] with the sender's node id and the bytes of each message that arrives on the remote path, until closed. */
    fun listen(onMessage: (nodeId: String, bytes: ByteArray) -> Unit): AutoCloseable
}
