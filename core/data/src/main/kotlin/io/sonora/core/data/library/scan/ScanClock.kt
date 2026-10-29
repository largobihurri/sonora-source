package io.sonora.core.data.library.scan

/** Wall-clock time for the scanner (date added, read retry spacing); replaced by a fake in tests. */
fun interface ScanClock {
    fun nowMs(): Long

    companion object {
        val System = ScanClock { java.lang.System.currentTimeMillis() }
    }
}
