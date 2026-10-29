package io.sonora.core.domain.port

import io.sonora.core.domain.model.ScanProgress
import kotlinx.coroutines.flow.StateFlow

/** Keeps the library index in sync with the library folders' files. */
interface LibraryScanner {
    val progress: StateFlow<ScanProgress>

    /**
     * Asks for an incremental scan of every library folder (tags are read only for new or changed files) and
     * returns at once; the scan runs in the background for as long as the app process lives. Scans never run
     * concurrently: requests made while a scan runs coalesce into ONE follow-up scan, so every request is
     * answered by a scan that started after it. Adding or removing a folder requests a scan by itself.
     */
    fun requestScan()
}
