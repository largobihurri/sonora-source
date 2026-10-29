package io.sonora.feature.cdjexport

import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolumes
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.domain.export.StickLocator
import io.sonora.core.domain.export.StickVolume
import io.sonora.feature.cdjexport.export.ExportJob
import io.sonora.feature.cdjexport.export.ExportRequest
import io.sonora.feature.cdjexport.export.ExportRunner
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLocator : StickLocator {
    val volumes = mutableMapOf<String, StickVolume>()

    override fun open(treeUri: String): StickVolume = volumes[treeUri] ?: throw StickIoException("No stick at $treeUri")
}

/** Takes every request (records it, shows it running) unless [refuse]: then it says another export runs. */
class FakeRunner : ExportRunner {
    override val job = MutableStateFlow<ExportJob>(ExportJob.Idle)
    val started = mutableListOf<Pair<StickVolume, ExportRequest>>()
    var refuse = false
    var cancelled = false
    var acknowledged = false

    override fun start(stick: StickVolume, request: ExportRequest): Boolean {
        if (refuse) return false
        started += stick to request
        job.value = ExportJob.Running(stick.name, request.tracks.size)
        return true
    }

    override fun cancel() {
        cancelled = true
    }

    override fun acknowledge() {
        acknowledged = true
        job.value = ExportJob.Idle
    }
}

/** A dispatcher that only runs what it was given when told to, in any order: to finish blocking work out of order. */
class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    val pending: Int get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun runFirst() = queue.removeFirst().run()

    fun runLast() = queue.removeLast().run()

    fun runAll() {
        while (queue.isNotEmpty()) runFirst()
    }
}

/** The removable volumes Android would report; set [current] to mount, eject or remove one. */
class FakeRemovableVolumes : RemovableVolumes {
    val current = MutableStateFlow<List<RemovableVolume>>(emptyList())

    override fun volumes() = current
}
