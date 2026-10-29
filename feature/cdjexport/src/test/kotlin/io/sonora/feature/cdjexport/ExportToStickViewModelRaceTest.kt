package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.StickVolume
import io.sonora.feature.cdjexport.export.FakeAnalyzer
import io.sonora.feature.cdjexport.export.FakeConverter
import io.sonora.feature.cdjexport.export.FakeSources
import io.sonora.feature.cdjexport.export.FakeStickFileSystem
import io.sonora.feature.cdjexport.export.FakeUriPermissions
import io.sonora.feature.cdjexport.export.StickExporter
import io.sonora.feature.cdjexport.export.cached
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The screen with the main thread and the I/O work interleaved as on a phone: main-thread work runs only when the test
 * says so ([StandardTestDispatcher]) and blocking I/O only when and in the order the test picks ([ManualDispatcher]).
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class ExportToStickViewModelRaceTest {

    private val main = StandardTestDispatcher()
    private val io = ManualDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val locator = FakeLocator()
    private val runner = FakeRunner()
    private val permissions = FakeUriPermissions()
    private val volumes = FakeRemovableVolumes()
    private lateinit var viewModel: ExportToStickViewModel

    private val state get() = viewModel.state.value

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        locator.volumes[STICK] = StickVolume("SONORA", isRemovableRoot = true, fileSystem = FakeStickFileSystem())
        locator.volumes[OTHER_STICK] = StickVolume("OTHER", isRemovableRoot = true, fileSystem = FakeStickFileSystem())
        viewModel = ExportToStickViewModel(
            sources,
            locator,
            StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources)),
            runner,
            permissions,
            io,
            clock,
            AppEnvironment("0.1.0 (1)", "16", 36, "Google Pixel 9"),
            ExportRequests(),
            volumes,
        )
        settle()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Runs everything queued on both threads until nothing is left. */
    private fun settle() {
        do {
            main.scheduler.advanceUntilIdle()
            val more = io.pending > 0
            io.runAll()
        } while (more)
    }

    @Test
    fun `clearing while picked files are looked at drops them and gives their access up`() {
        val picked = listOf(sources.add("a.aiff").uri, sources.add("b.aiff").uri)
        viewModel.onFilesPicked(picked)
        main.scheduler.runCurrent()
        io.runFirst() // access kept, inspection not started
        assertThat(state.inspecting).isTrue()

        viewModel.clearTracks()
        settle()

        assertThat(state.tracks).isEmpty()
        assertThat(state.rejected).isEmpty()
        assertThat(state.inspecting).isFalse()
        assertThat(permissions.kept).isEmpty()
    }

    @Test
    fun `removing a track while a pick with the same file is looked at keeps the access that pick needs`() {
        val a = sources.add("a.aiff").uri
        viewModel.onFilesPicked(listOf(a))
        settle()
        val c = sources.add("c.aiff").uri
        viewModel.onFilesPicked(listOf(a, c))
        main.scheduler.runCurrent()
        io.runFirst() // the second pick has kept access to a and c

        viewModel.removeTrack(a)
        settle()

        assertThat(state.tracks.map { it.uri }).containsExactly(a, c).inOrder()
        assertThat(permissions.kept).containsExactly(a, c)
    }

    @Test
    fun `picking another stick while one is opening ends with the last one picked`() {
        viewModel.onStickPicked(STICK)
        main.scheduler.runCurrent()
        viewModel.onStickPicked(OTHER_STICK)
        main.scheduler.runCurrent()
        assertThat(state.openingStick).isTrue()

        io.runLast() // the second stick opens first
        main.scheduler.advanceUntilIdle()
        settle() // then whatever the first pick left

        assertThat(state.stick!!.treeUri).isEqualTo(OTHER_STICK)
        assertThat(state.stick!!.name).isEqualTo("OTHER")
        assertThat(state.openingStick).isFalse()
        viewModel.onFilesPicked(listOf(sources.add("a.aiff").uri))
        settle()
        viewModel.startExport()
        assertThat(runner.started.single().first.name).isEqualTo("OTHER")
    }

    @Test
    fun `ejecting a stick that is still opening lets go of it, and it does not open by itself again`() {
        permissions.heldTrees += STICK
        volumes.current.value = listOf(RemovableVolume("1234-ABCD", "SONORA", 16L shl 30, RemovableVolume.State.MOUNTED))
        main.scheduler.runCurrent()
        io.runAll() // the held grants are read
        main.scheduler.runCurrent() // the stick starts opening by itself, its opening waits for I/O
        assertThat(state.openingStick).isTrue()

        val letGo = viewModel.forgetStick()
        settle()

        assertThat(letGo).isEqualTo("1234-ABCD")
        assertThat(state.stick).isNull()
        assertThat(state.openingStick).isFalse()

        // Android reports the stick again (still mounted while the user goes to eject it).
        volumes.current.value = listOf(RemovableVolume("1234-ABCD", "SONORA", 1, RemovableVolume.State.MOUNTED))
        settle()

        assertThat(state.stick).isNull()
        assertThat(state.openingStick).isFalse()
    }

    private companion object {
        const val STICK = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A"
        const val OTHER_STICK = "content://com.android.externalstorage.documents/tree/5678-EF01%3A"
    }
}
