package io.sonora.core.watchphone.remote

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import io.sonora.core.watchsync.remote.RemotePaths
import javax.inject.Inject

/**
 * Woken by the Data Layer when the watch sends a remote message, even if Sonora is not running. It only passes the message on:
 * [RemoteHost] checks the sender and decides what to do.
 */
@AndroidEntryPoint
class RemoteListenerService : WearableListenerService() {

    @Inject
    lateinit var host: RemoteHost

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == RemotePaths.MESSAGE_PATH) host.onMessage(event.sourceNodeId, event.data)
    }
}
