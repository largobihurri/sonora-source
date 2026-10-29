package io.sonora.feature.cdjexport.export

import android.util.Log
import io.sonora.core.domain.export.StickVolume
import io.sonora.core.domain.log.redactForLog
import io.sonora.feature.cdjexport.di.CdjExportIoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the app-wide export is. */
sealed interface ExportJob {
    data object Idle : ExportJob

    /** Exporting to [stickName]; [progress] is `null` until the first track starts. */
    data class Running(
        val stickName: String,
        val trackCount: Int,
        val progress: ExportProgress? = null,
        val cancelling: Boolean = false,
    ) : ExportJob

    /** The last export ended with [result]; it stays until the user has seen it ([ExportRunner.acknowledge]). */
    data class Finished(val stickName: String, val result: ExportResult) : ExportJob
}

/** Runs one export at a time, outside any screen, so it survives configuration changes and leaving the app. */
interface ExportRunner {
    val job: StateFlow<ExportJob>

    /**
     * Starts exporting [request] to [stick]: `true` when the request was taken (its outcome arrives through [job],
     * even when it fails at once), `false` (and nothing happens) while another export runs.
     */
    fun start(stick: StickVolume, request: ExportRequest): Boolean

    /** Asks the running export to stop; it removes what it copied and ends as [ExportResult.Cancelled]. */
    fun cancel()

    /** Forgets a [ExportJob.Finished] export, back to [ExportJob.Idle]. */
    fun acknowledge()
}

/** Keeps the process in the foreground (a notification) while an export runs. */
fun interface ExportForeground {
    fun start()
}

/**
 * The app's [ExportRunner]: runs [StickExporter] in its own scope on the I/O dispatcher and asks [foreground] to keep
 * the process alive meanwhile (the foreground service watches [job] and stops itself when the export ends).
 *
 * It owns the read access of a started export's tracks ([ExportRequest.grants]) and gives it up when the export ends,
 * whatever became of the screen that picked them. Once per run of the app, it first gives up the access an earlier run
 * kept and never gave up ([UriPermissions.releaseLeftovers]).
 */
@Singleton
class ExportCoordinator @Inject constructor(
    private val exporter: StickExporter,
    private val foreground: ExportForeground,
    private val permissions: UriPermissions,
    @CdjExportIoDispatcher io: CoroutineDispatcher,
) : ExportRunner {

    private val scope = CoroutineScope(SupervisorJob() + io)

    init {
        scope.launch { quietly("Giving up access left by an earlier run") { permissions.releaseLeftovers() } }
    }
    private val _job = MutableStateFlow<ExportJob>(ExportJob.Idle)
    override val job: StateFlow<ExportJob> = _job.asStateFlow()

    private var running: Job? = null

    @Synchronized
    override fun start(stick: StickVolume, request: ExportRequest): Boolean {
        if (_job.value is ExportJob.Running) return false
        _job.value = ExportJob.Running(stick.name, request.tracks.size)
        try {
            foreground.start()
        } catch (e: Exception) {
            // Android may refuse the foreground service (started from the background, say). Exporting without it
            // could be killed half-way, so nothing is written and the next start works again.
            _job.value = ExportJob.Finished(stick.name, ExportResult.Failed(STEP_STARTING, e))
            scope.launch { releaseGrants(request) }
            return true
        }
        running = scope.launch {
            val result = try {
                exporter.export(stick.fileSystem, request) { progress ->
                    _job.update { (it as? ExportJob.Running)?.copy(progress = progress) ?: it }
                }
            } catch (e: CancellationException) {
                ExportResult.Cancelled
            } catch (e: Exception) {
                // StickExporter reports its own failures (with libraryChanged); this only guards against a bug in it.
                ExportResult.Failed("Exporting", e)
            }
            _job.value = ExportJob.Finished(stick.name, result)
            releaseGrants(request)
        }
        return true
    }

    @Synchronized
    override fun cancel() {
        if (_job.value !is ExportJob.Running) return
        _job.update { (it as? ExportJob.Running)?.copy(cancelling = true) ?: it }
        running?.cancel()
    }

    @Synchronized
    override fun acknowledge() {
        if (_job.value is ExportJob.Finished) _job.value = ExportJob.Idle
    }

    private fun releaseGrants(request: ExportRequest) =
        request.grants.forEach { uri -> quietly("Giving up access to ${redactForLog(uri)}") { permissions.release(uri) } }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Best effort: a grant left behind is given up by the next run of the app.
            Log.w(TAG, "$what failed", e)
        }
    }

    private companion object {
        const val TAG = "SonoraExport"
        const val STEP_STARTING = "Starting the export in the foreground"
    }
}
