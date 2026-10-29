package io.sonora.core.data.library.duplicates

import android.util.Log
import io.sonora.core.domain.library.duplicates.BacklogAction
import io.sonora.core.domain.library.duplicates.DuplicateWork
import io.sonora.core.domain.library.duplicates.ReviewDuplicateBacklog
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.port.LibraryScanner
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * What starts the background duplicate checks, for the lifetime of the process ([start] at app start): the daily scan
 * is scheduled (kept when already there), and every finished library scan reviews the backlog of songs not checked yet
 * ([ReviewDuplicateBacklog]: the first analysis of a new library, or a catch-up).
 */
@Singleton
class DuplicateTriggers internal constructor(
    private val scope: CoroutineScope,
    private val libraryScans: Flow<ScanProgress>,
    private val schedulePeriodic: () -> Unit,
    private val review: suspend () -> BacklogAction,
    private val onError: (Throwable) -> Unit,
) {
    @Inject
    constructor(scanner: LibraryScanner, work: DuplicateWork, review: ReviewDuplicateBacklog) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        libraryScans = scanner.progress,
        schedulePeriodic = work::schedulePeriodic,
        review = { review() },
        onError = { Log.w(TAG, "Cannot review the duplicates backlog", it) },
    )

    private val started = AtomicBoolean(false)

    /** Schedules the daily scan and follows the library scans; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            schedulePeriodic()
        } catch (e: IllegalStateException) {
            onError(e) // WorkManager not initialised (never in the app).
        }
        scope.launch {
            libraryScans.filterIsInstance<ScanProgress.Finished>().collect {
                try {
                    review()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SonoraDuplicates"
    }
}
