package io.sonora.core.decoder

import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A decode path (AIFF reader, platform codec) with the [PcmStream] contract, but blocking. */
internal interface BlockingPcmReader : AutoCloseable {
    val format: PcmFormat

    /** As [PcmStream.read]; the caller has checked that [buffer] holds at least one frame. */
    fun read(buffer: FloatArray): Int

    /**
     * Asks a [read] running on another thread to stop soon (it may then throw [StreamClosedException]). Called from
     * any thread, before [close]; the default does nothing, for readers whose reads never stall.
     */
    fun cancel() = Unit

    override fun close()
}

/** A read stopped because its stream was closed meanwhile. */
internal class StreamClosedException : IllegalStateException("PcmStream was closed during the read")

/**
 * Runs a [BlockingPcmReader] on [dispatcher]. A cancelled caller stops before the next block.
 *
 * [close] never blocks the caller (it may be the main thread) and never throws. Without a read in progress it releases
 * the reader at once. During a read it asks the reader to stop ([BlockingPcmReader.cancel]) and returns: the reader
 * is released on the reading thread when that read returns, so a codec is never released under a running decode,
 * or, if the read has not returned after [forceReleaseAfterMs], on a background thread anyway. Either way exactly once.
 */
internal class DispatchedPcmStream(
    private val reader: BlockingPcmReader,
    private val dispatcher: CoroutineDispatcher,
    private val forceReleaseAfterMs: Long = FORCE_RELEASE_AFTER_MS,
) : PcmStream {

    /** Serialises reads; [close] never takes it. */
    private val readLock = Any()

    /** Guards [closed] and [readInProgress]; held only briefly. */
    private val stateLock = Any()
    private var closed = false

    /** Counted down when the read in progress returns; null between reads. */
    private var readInProgress: CountDownLatch? = null

    private val released = AtomicBoolean(false)

    override val format: PcmFormat get() = reader.format

    override suspend fun read(buffer: FloatArray): Int {
        require(buffer.size >= format.channelCount) {
            "A buffer of ${buffer.size} samples cannot hold one frame of ${format.channelCount} channels"
        }
        currentCoroutineContext().ensureActive()
        return withContext(dispatcher) {
            synchronized(readLock) {
                val done = CountDownLatch(1)
                synchronized(stateLock) {
                    check(!closed) { "PcmStream is closed" }
                    readInProgress = done
                }
                try {
                    reader.read(buffer)
                } finally {
                    val closedMeanwhile = synchronized(stateLock) {
                        readInProgress = null
                        closed
                    }
                    done.countDown()
                    if (closedMeanwhile) release()
                }
            }
        }
    }

    override fun close() {
        val inProgress = synchronized(stateLock) {
            if (closed) return
            closed = true
            readInProgress
        } ?: return release()
        runCatching { reader.cancel() }
        runCatching {
            thread(isDaemon = true, name = "PcmStream release") {
                if (!inProgress.await(forceReleaseAfterMs, TimeUnit.MILLISECONDS)) release()
            }
        }
    }

    private fun release() {
        if (released.compareAndSet(false, true)) runCatching { reader.close() }
    }

    private companion object {
        /** Longer than a stalled codec takes to give up on its own (about five seconds of idle rounds). */
        const val FORCE_RELEASE_AFTER_MS = 10_000L
    }
}

/**
 * Opens a reader with the blocking [open] on [dispatcher]. If the caller is cancelled while it opens, the reader
 * that was opened anyway is closed instead of leaking.
 */
internal suspend fun openOn(dispatcher: CoroutineDispatcher, open: () -> BlockingPcmReader): PcmStream {
    var opened: BlockingPcmReader? = null
    try {
        withContext(dispatcher) { opened = open() }
    } catch (e: CancellationException) {
        runCatching { opened?.close() }
        throw e
    }
    return DispatchedPcmStream(checkNotNull(opened), dispatcher)
}
