package io.sonora.core.watchlink

import com.google.android.gms.wearable.ChannelClient
import io.sonora.core.watchsync.SyncStreams

/** The two streams of an open Data Layer [channel] as [SyncStreams]; closing them closes the channel. */
suspend fun ChannelClient.syncStreams(channel: ChannelClient.Channel): SyncStreams {
    val output = getOutputStream(channel).awaitIo()
    val input = getInputStream(channel).awaitIo()
    return SyncStreams(input, output) { close(channel) }
}
