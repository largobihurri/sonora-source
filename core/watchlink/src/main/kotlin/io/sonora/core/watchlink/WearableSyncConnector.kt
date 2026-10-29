package io.sonora.core.watchlink

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.sonora.core.watchsync.SyncConnector
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.WatchNode
import io.sonora.core.watchsync.WatchNodes
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.WatchSyncPaths

/**
 * The phone's connection to the watch: finds the reachable node that declares the Sonora capability and opens the sync
 * channel to it. The watch app is woken by the channel (its listener service), so it need not be running.
 */
class WearableSyncConnector(context: Context) : SyncConnector {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val channels = Wearable.getChannelClient(context)

    override suspend fun connect(): SyncStreams {
        val node = findWatch() ?: throw WatchNotFoundException()
        val channel = channels.openChannel(node.id, WatchSyncPaths.SYNC_CHANNEL).awaitIo()
        return try {
            channels.syncStreams(channel)
        } catch (e: Exception) {
            channels.close(channel)
            throw e
        }
    }

    private suspend fun findWatch(): WatchNode? {
        val capability = capabilities.getCapability(WatchSyncPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).awaitIo()
        return WatchNodes.pick(capability.nodes.map { WatchNode(it.id, it.displayName, it.isNearby) })
    }
}
