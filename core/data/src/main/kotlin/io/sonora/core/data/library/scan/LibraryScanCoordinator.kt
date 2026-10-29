package io.sonora.core.data.library.scan

import android.util.Log
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.port.LibraryScanner
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-scoped [LibraryScanner]: one loop in [scope] runs every scan, so two scans never overlap.
 *
 * Policy: coalesce, not cancel-and-restart. Requests land in a conflated channel; while a scan runs, any number
 * of requests leave exactly one pending follow-up scan, which starts after the current one ends. Scans are
 * incremental, so the follow-up only reads what changed meanwhile, and no finished tag reads are thrown away. A
 * folder removed mid-scan is not waited on: the scan stops reading it at the next batch. A failing scan never stops
 * the loop; only cancelling [scope] does.
 *
 * [start] (called once at app start) watches the folder set and requests a scan for its first value and every
 * change, which covers app start, folder added and folder removed; [requestScan] covers manual rescans.
 */
@Singleton
class LibraryScanCoordinator internal constructor(
    private val scope: CoroutineScope,
    override val progress: StateFlow<ScanProgress>,
    private val folderUris: Flow<Set<String>>,
    private val scan: suspend () -> Unit,
    private val onError: (Throwable) -> Unit,
) : LibraryScanner {

    @Inject
    constructor(service: LibraryScanService, dao: LibraryDao) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        progress = service.progress,
        folderUris = dao.folders().map { rows -> rows.mapTo(HashSet()) { it.treeUri } },
        scan = { service.scan() },
        onError = { Log.w(TAG, "Library scan failed", it) },
    )

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    scan()
                } catch (e: Throwable) {
                    // Only cancelling [scope] ends the loop; any other failure (a cancellation from inside the scan,
                    // an Error) is reported and the next request still scans. The scan itself ends its progress.
                    if (e is CancellationException) ensureActive()
                    onError(e)
                }
            }
        }
    }

    /** Starts watching the library folders; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { folderUris.distinctUntilChanged().collect { requestScan() } }
    }

    override fun requestScan() {
        requests.trySend(Unit)
    }

    private companion object {
        const val TAG = "SonoraScan"
    }
}
