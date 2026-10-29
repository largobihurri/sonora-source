package io.sonora.core.watchsync

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two ends of one sync connection: what the Data Layer's channel gives each side. Closing it (once; more is a no-op)
 * closes both streams and the underlying channel, and wakes any thread blocked reading or writing.
 */
class SyncStreams(
    val input: InputStream,
    val output: OutputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}

/** Opens a connection to the other device: the phone's to the watch. The Data Layer channel is the only real one. */
fun interface SyncConnector {
    /**
     * @throws WatchNotFoundException when no watch can be reached.
     * @throws java.io.IOException when the connection cannot be opened right now (worth retrying).
     */
    suspend fun connect(): SyncStreams
}
