package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.domain.analysis.TrackAnalyzer
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.StickVolume
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val stick = FakeStickFileSystem()

    /** Analysing a track whose URI has a gate waits until the gate opens. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val analyzer = object : TrackAnalyzer {
        override suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit): TrackAnalysis {
            onProgress(0.5f)
            gates[sourceUri]?.await()
            return FakeAnalyzer.ANALYSIS
        }
    }
    private var foregroundStarts = 0
    private var foregroundFailure: RuntimeException? = null
    private val permissions = FakeUriPermissions()
    private val coordinator = ExportCoordinator(
        StickExporter(sources, analyzer.cached(), clock, FakeConverter(sources)),
        {
            foregroundFailure?.let { throw it }
            foregroundStarts++
        },
        permissions,
        dispatcher,
    )
    private val volume = StickVolume("SONORA", isRemovableRoot = true, fileSystem = stick)

    private fun runTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun gated(name: String): SourceFile = sources.add(name).also { gates[it.uri] = CompletableDeferred() }

    @Test
    fun `an export runs in the foreground, reports progress and keeps its result`() = runTest {
        val a = gated("a.aiff")

        assertThat(coordinator.start(volume, ExportRequest(listOf(a), "Set"))).isTrue()
        advanceUntilIdle()

        val running = coordinator.job.value as ExportJob.Running
        assertThat(running.stickName).isEqualTo("SONORA")
        assertThat(running.progress?.phase).isEqualTo(ExportPhase.ANALYSING)
        assertThat(foregroundStarts).isEqualTo(1)

        gates.getValue(a.uri).complete(Unit)
        advanceUntilIdle()

        val finished = coordinator.job.value as ExportJob.Finished
        assertThat((finished.result as ExportResult.Completed).exported).hasSize(1)
        coordinator.acknowledge()
        assertThat(coordinator.job.value).isEqualTo(ExportJob.Idle)
    }

    @Test
    fun `only one export runs at a time and the refused one changes nothing`() = runTest {
        val a = gated("a.aiff")
        coordinator.start(volume, ExportRequest(listOf(a), null))
        advanceUntilIdle()

        assertThat(coordinator.start(volume, ExportRequest(listOf(sources.add("b.aiff")), "Other"))).isFalse()
        assertThat((coordinator.job.value as ExportJob.Running).trackCount).isEqualTo(1)
        gates.getValue(a.uri).complete(Unit)
        advanceUntilIdle()

        val result = (coordinator.job.value as ExportJob.Finished).result as ExportResult.Completed
        assertThat(result.exported.map { it.name }).containsExactly("a.aiff")
        assertThat(result.playlist).isNull()
        assertThat(stick.paths.filter { it.startsWith("Contents/") }).containsExactly("Contents/UnknownArtist/UnknownAlbum/a.aiff")
        assertThat(foregroundStarts).isEqualTo(1)
    }

    @Test
    fun `a cancelled export ends as cancelled and removes the files it had already written`() = runTest {
        val b = gated("b.aiff")
        coordinator.start(volume, ExportRequest(listOf(sources.add("a.aiff"), b), "Set"))
        advanceUntilIdle()
        assertThat(stick.paths).contains("Contents/UnknownArtist/UnknownAlbum/a.aiff")
        assertThat(stick.paths.count { it.startsWith("PIONEER/USBANLZ/") }).isEqualTo(2)

        coordinator.cancel()
        assertThat((coordinator.job.value as ExportJob.Running).cancelling).isTrue()
        advanceUntilIdle()

        assertThat((coordinator.job.value as ExportJob.Finished).result).isEqualTo(ExportResult.Cancelled)
        assertThat(stick.paths).isEmpty()
        assertThat(stick.log.filter { it.startsWith("delete ") }).hasSize(3)
    }

    @Test
    fun `an export that cannot go to the foreground fails cleanly and the next one can start`() = runTest {
        foregroundFailure = IllegalStateException("Foreground service not allowed")

        val a = sources.add("a.aiff")
        assertThat(coordinator.start(volume, ExportRequest(listOf(a), null, grants = setOf(a.uri)))).isTrue()
        advanceUntilIdle()

        val failed = (coordinator.job.value as ExportJob.Finished).result as ExportResult.Failed
        assertThat(failed.cause).isSameInstanceAs(foregroundFailure)
        assertThat(failed.libraryChanged).isFalse()
        assertThat(stick.paths).isEmpty()
        assertThat(permissions.released).containsExactly("content://picked/a.aiff")

        foregroundFailure = null
        coordinator.acknowledge()
        assertThat(coordinator.start(volume, ExportRequest(listOf(sources.add("b.aiff")), null))).isTrue()
        advanceUntilIdle()

        assertThat(((coordinator.job.value as ExportJob.Finished).result as ExportResult.Completed).exported).hasSize(1)
    }

    @Test
    fun `an unexpected failure while the library is replaced says the library changed`() = runTest {
        stick.onReplace = { if (it == StickExporter.PDB_PATH) throw IllegalStateException("provider died") }

        coordinator.start(volume, ExportRequest(listOf(sources.add("a.aiff")), null))
        advanceUntilIdle()

        val failed = (coordinator.job.value as ExportJob.Finished).result as ExportResult.Failed
        assertThat(failed.libraryChanged).isTrue()
        assertThat(stick.exists("Contents/UnknownArtist/UnknownAlbum/a.aiff")).isTrue()
    }

    @Test
    fun `the tracks' access is given up when the export ends, however it ends`() = runTest {
        val a = gated("a.aiff")
        coordinator.start(volume, ExportRequest(listOf(a), null, grants = setOf(a.uri, "content://tree/music")))
        advanceUntilIdle()
        assertThat(permissions.released).isEmpty()

        coordinator.cancel()
        advanceUntilIdle()

        assertThat(permissions.released).containsExactly(a.uri, "content://tree/music")
    }

    @Test
    fun `access an earlier run of the app left behind is given up once`() = runTest {
        advanceUntilIdle()

        assertThat(permissions.leftoverReleases).isEqualTo(1)
    }
}
