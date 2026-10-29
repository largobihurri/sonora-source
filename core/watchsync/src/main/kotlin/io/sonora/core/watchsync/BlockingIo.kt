package io.sonora.core.watchsync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs blocking stream code on [io] so that cancelling the caller really stops it: a thread blocked in a stream read or
 * write cannot be interrupted, but closing the [streams] wakes it up, so cancelling closes them.
 */
internal suspend fun <T> runBlockingIo(streams: SyncStreams, io: CoroutineDispatcher, block: () -> T): T = coroutineScope {
    val work = async(io) { block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        streams.close()
        throw e
    }
}
