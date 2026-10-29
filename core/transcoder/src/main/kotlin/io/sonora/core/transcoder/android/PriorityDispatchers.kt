package io.sonora.core.transcoder.android

import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Conversion threads per Linux nice value (`ConversionBudget.threadPriority`), so a Low Resources batch never
 * competes with the UI or playback. Pools grow with the batch's concurrency and release idle threads after a
 * minute.
 */
@Singleton
class PriorityDispatchers @Inject constructor() {

    private val pools = ConcurrentHashMap<Int, CoroutineDispatcher>()

    fun forPriority(priority: Int): CoroutineDispatcher = pools.computeIfAbsent(priority) {
        val count = AtomicInteger()
        Executors.newCachedThreadPool { work ->
            Thread({
                Process.setThreadPriority(priority)
                work.run()
            }, "sonora-convert-$priority-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
