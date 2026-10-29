package io.sonora.core.decoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.decoder.TestAiff.int16Be
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class DispatchedPcmStreamTest {

    private val format = PcmFormat(44_100, 2, null, "test", durationUs = null, totalFrames = null)

    private class FakeReader(override val format: PcmFormat, private var blocks: Int = Int.MAX_VALUE) : BlockingPcmReader {
        var reads = 0
        var closes = 0

        override fun read(buffer: FloatArray): Int {
            reads++
            if (blocks-- <= 0) return -1
            buffer.fill(0.5f, 0, format.channelCount)
            return 1
        }

        override fun close() {
            closes++
        }
    }

    @Test
    fun `reads run on the dispatcher and pass the reader's result through`() = runTest {
        val reader = FakeReader(format, blocks = 1)
        val stream = DispatchedPcmStream(reader, StandardTestDispatcher(testScheduler))
        val buffer = FloatArray(4)

        assertThat(stream.format).isEqualTo(format)
        assertThat(stream.read(buffer)).isEqualTo(1)
        assertThat(buffer[1]).isEqualTo(0.5f)
        assertThat(stream.read(buffer)).isEqualTo(-1)
    }

    @Test
    fun `a buffer smaller than one frame is rejected before reading`() = runTest {
        val reader = FakeReader(format)
        val stream = DispatchedPcmStream(reader, StandardTestDispatcher(testScheduler))

        val error = runCatching { stream.read(FloatArray(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(reader.reads).isEqualTo(0)
    }

    @Test
    fun `close is idempotent and later reads fail`() = runTest {
        val reader = FakeReader(format)
        val stream = DispatchedPcmStream(reader, StandardTestDispatcher(testScheduler))

        stream.close()
        stream.close()

        assertThat(reader.closes).isEqualTo(1)
        assertThat(runCatching { stream.read(FloatArray(4)) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(reader.reads).isEqualTo(0)
    }

    @Test
    fun `cancelling the reading coroutine stops before the next block`() = runTest {
        val reader = FakeReader(format)
        val stream = DispatchedPcmStream(reader, StandardTestDispatcher(testScheduler))
        var delivered = 0
        var failure: Throwable? = null

        val job = launch {
            try {
                val buffer = FloatArray(4)
                while (stream.read(buffer) >= 0) {
                    if (++delivered == 3) coroutineContext[Job]!!.cancel()
                }
            } catch (e: CancellationException) {
                failure = e
                throw e
            } finally {
                stream.close()
            }
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(delivered).isEqualTo(3)
        assertThat(reader.reads).isEqualTo(3)
        assertThat(reader.closes).isEqualTo(1)
    }

    @Test
    fun `cancelling an AIFF decode midway stops it and releases the file`() = runTest {
        val bytes = TestAiff.build(int16Be(*IntArray(20_000)), channels = 2, frames = 10_000, bits = 16)
        var closed = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        var frames = 0

        val job = launch {
            openOn(dispatcher) {
                AiffPcmReader.open("content://test/a.aiff") {
                    object : ByteArrayInputStream(bytes) {
                        override fun close() {
                            closed = true
                        }
                    }
                }
            }.use { stream ->
                val buffer = FloatArray(1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    frames += read
                    if (frames >= 2048) coroutineContext[Job]!!.cancel()
                }
            }
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(frames).isEqualTo(2048)
        assertThat(closed).isTrue()
    }

    @Test
    fun `a reader opened while the caller is cancelled is closed, not leaked`() = runTest {
        val reader = FakeReader(format)
        lateinit var job: Job
        var result: PcmStream? = null

        job = launch {
            result = openOn(StandardTestDispatcher(testScheduler)) {
                job.cancel()
                reader
            }
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(result).isNull()
        assertThat(reader.closes).isEqualTo(1)
    }

    @Test
    fun `failures while opening propagate unchanged`() = runTest {
        val error = runCatching {
            openOn(StandardTestDispatcher(testScheduler)) { throw IllegalStateException("boom") }
        }.exceptionOrNull()

        // Coroutine stack-trace recovery may hand back a copy, so compare type and message.
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("boom")
    }

    @Test
    fun `failures from the reader propagate unchanged`() {
        val reader = object : BlockingPcmReader {
            override val format = this@DispatchedPcmStreamTest.format

            override fun read(buffer: FloatArray): Int = throw java.io.IOException("x")

            override fun close() = Unit
        }

        assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking { DispatchedPcmStream(reader, kotlinx.coroutines.Dispatchers.Unconfined).read(FloatArray(2)) }
        }
    }

    /** A reader whose read blocks until [proceed] opens (or [MAX_BLOCK_SECONDS] pass), like a stalled codec. */
    private class StalledReader(override val format: PcmFormat) : BlockingPcmReader {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancels = AtomicInteger()
        val closes = AtomicInteger()
        val readThread = AtomicReference<Thread>()
        val closeThread = AtomicReference<Thread>()

        override fun read(buffer: FloatArray): Int {
            readThread.set(Thread.currentThread())
            entered.countDown()
            proceed.await(MAX_BLOCK_SECONDS, TimeUnit.SECONDS)
            return 1
        }

        override fun cancel() {
            cancels.incrementAndGet()
        }

        override fun close() {
            closes.incrementAndGet()
            closeThread.set(Thread.currentThread())
            released.countDown()
        }
    }

    private fun withReaderThread(test: (dispatcher: kotlinx.coroutines.CoroutineDispatcher) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            test(executor.asCoroutineDispatcher())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun startRead(stream: PcmStream): CompletableFuture<Int> =
        CompletableFuture.supplyAsync { runBlocking { stream.read(FloatArray(4)) } }

    @Test
    fun `close returns at once during a stalled read and the reader thread releases afterwards`() = withReaderThread { dispatcher ->
        val reader = StalledReader(format)
        val stream = DispatchedPcmStream(reader, dispatcher, forceReleaseAfterMs = 60_000)
        val read = startRead(stream)
        check(reader.entered.await(5, TimeUnit.SECONDS))

        val closing = measureTimeMillis { stream.close() }

        assertThat(closing).isLessThan(500L)
        assertThat(reader.cancels.get()).isEqualTo(1)
        assertThat(reader.closes.get()).isEqualTo(0)
        reader.proceed.countDown()
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(1)
        assertThat(reader.released.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reader.closeThread.get()).isSameInstanceAs(reader.readThread.get())
        stream.close()
        assertThat(reader.closes.get()).isEqualTo(1)
        assertThat(runCatching { runBlocking { stream.read(FloatArray(4)) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a read that never returns is released after the bounded wait, exactly once`() = withReaderThread { dispatcher ->
        val reader = StalledReader(format)
        val stream = DispatchedPcmStream(reader, dispatcher, forceReleaseAfterMs = 100)
        val read = startRead(stream)
        check(reader.entered.await(5, TimeUnit.SECONDS))

        val closing = measureTimeMillis { stream.close() }

        assertThat(closing).isLessThan(500L)
        assertThat(reader.released.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(reader.closeThread.get()).isNotSameInstanceAs(reader.readThread.get())
        reader.proceed.countDown()
        read.get(5, TimeUnit.SECONDS)
        stream.close()
        assertThat(reader.closes.get()).isEqualTo(1)
    }

    @Test
    fun `close never throws, even when cancelling or releasing the reader fails`() = withReaderThread { dispatcher ->
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val reader = object : BlockingPcmReader {
            override val format = this@DispatchedPcmStreamTest.format

            override fun read(buffer: FloatArray): Int {
                entered.countDown()
                proceed.await(MAX_BLOCK_SECONDS, TimeUnit.SECONDS)
                return -1
            }

            override fun cancel() = throw IllegalStateException("cancel failed")

            override fun close() {
                closes.incrementAndGet()
                throw IllegalStateException("release failed")
            }
        }
        val stream = DispatchedPcmStream(reader, dispatcher, forceReleaseAfterMs = 60_000)
        val read = startRead(stream)
        check(entered.await(5, TimeUnit.SECONDS))

        stream.close()
        proceed.countDown()

        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(-1)
        stream.close()
        assertThat(closes.get()).isEqualTo(1)
    }

    private companion object {
        const val MAX_BLOCK_SECONDS = 3L
    }
}
