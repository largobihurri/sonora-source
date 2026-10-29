package io.sonora.core.data.library.scan

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.ScanProgress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy
class LibraryScanCoordinatorTest {

    private var scans = 0
    private var running = 0
    private var maxRunning = 0
    private val errors = mutableListOf<Throwable>()

    /** A scan that takes one virtual second and records overlap. */
    private val slowScan: suspend () -> Unit = {
        scans++
        running++
        maxRunning = maxOf(maxRunning, running)
        try {
            delay(1_000)
        } finally {
            running--
        }
    }

    /** Lets the scan loop (a backgroundScope coroutine, which advanceUntilIdle ignores) run to rest. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun TestScope.coordinator(folders: Flow<Set<String>> = emptyFlow(), scan: suspend () -> Unit = slowScan) =
        LibraryScanCoordinator(backgroundScope, MutableStateFlow(ScanProgress.Idle), folders, scan, onError = { errors += it })

    @Test
    fun `a request runs one scan`() = runTest {
        coordinator().requestScan()
        settle()
        assertThat(scans).isEqualTo(1)
    }

    @Test
    fun `requests during a scan coalesce into one follow-up scan and scans never overlap`() = runTest {
        val c = coordinator()
        c.requestScan()
        runCurrent()
        assertThat(running).isEqualTo(1)

        repeat(3) { c.requestScan() }
        settle()

        assertThat(scans).isEqualTo(2)
        assertThat(maxRunning).isEqualTo(1)
    }

    @Test
    fun `a failing scan is reported and later requests still scan`() = runTest {
        var fail = true
        val c = coordinator { scans++; if (fail) error("volume gone") }
        c.requestScan()
        settle()
        fail = false
        c.requestScan()
        settle()
        assertThat(scans).isEqualTo(2)
        assertThat(errors.map { it.message }).containsExactly("volume gone")
    }

    @Test
    fun `a scan ending in a foreign cancellation or an error does not stop later scans`() = runTest {
        val failures = ArrayDeque<Throwable>(listOf(CancellationException("scan's own job"), NotImplementedError("bug")))
        val c = coordinator { scans++; failures.removeFirstOrNull()?.let { throw it } }

        repeat(3) {
            c.requestScan()
            settle()
        }

        assertThat(scans).isEqualTo(3)
        assertThat(errors.map { it.message }).containsExactly("scan's own job", "bug").inOrder()
    }

    @Test
    fun `cancelling the coordinator scope ends the scan loop`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val c = LibraryScanCoordinator(scope, MutableStateFlow(ScanProgress.Idle), emptyFlow(), slowScan, onError = { errors += it })
        c.requestScan()
        runCurrent()

        scope.cancel()
        c.requestScan()
        settle()

        assertThat(scans).isEqualTo(1)
        assertThat(running).isEqualTo(0)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `folder set changes request scans and re-emitted equal sets do not`() = runTest {
        val folders = MutableStateFlow(setOf("a"))
        val c = coordinator(folders)
        c.start()
        settle()
        assertThat(scans).isEqualTo(1) // app start with folders

        folders.value = setOf("a", "b") // added
        settle()
        assertThat(scans).isEqualTo(2)

        folders.value = setOf("b", "a")
        settle()
        assertThat(scans).isEqualTo(2)

        folders.value = setOf("b") // removed
        settle()
        assertThat(scans).isEqualTo(3)
    }

    @Test
    fun `starting twice watches the folders once`() = runTest {
        val c = coordinator(MutableStateFlow(setOf("a")))
        c.start()
        c.start()
        settle()
        assertThat(scans).isEqualTo(1)
    }
}
