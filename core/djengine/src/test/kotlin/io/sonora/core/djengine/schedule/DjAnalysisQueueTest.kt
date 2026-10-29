package io.sonora.core.djengine.schedule

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.SchedulingInput
import io.sonora.core.domain.audio.PcmDecodeException
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DjAnalysisQueueTest {

    /** Stable fake audio identity for a uri; [alias] maps several uris to one id. */
    private fun idOf(uri: String): AudioId =
        AudioId.of(AudioId.PAYLOAD_V1, (uri.hashCode().toLong() and 0xffffffffL).toString(16).padStart(64, '0'))

    /** A queue wired to controllable fakes; every fake appends to [log]. */
    private class Harness(
        test: TestScope,
        scope: CoroutineScope,
        upNextLimit: Int = 3,
        private val idOf: (String) -> AudioId,
    ) {
        val input = MutableStateFlow(SchedulingInput(djModeOn = false, playingUri = null, upNextUris = emptyList()))
        val log = mutableListOf<String>()
        val durations = mutableMapOf<String, Long>()
        val failing = mutableSetOf<String>()
        val identifyFailing = mutableSetOf<String>()
        val failures = mutableMapOf<String, Exception>()
        val stored = mutableSetOf<AudioId>()
        val identifyDelays = mutableMapOf<String, Long>()
        val traces = mutableListOf<ScheduleTrace>()
        var persist = true
        private val testScope = test

        val queue = DjAnalysisQueue(
            input = input,
            identify = { uri ->
                log += "identify:$uri"
                identifyDelays[uri]?.let { delay(it) }
                if (uri in identifyFailing) throw failures[uri] ?: IllegalStateException("unreadable $uri")
                idOf(uri)
            },
            isAnalysed = { it in stored },
            analyze = { uri, id, onProgress ->
                log += "start:$uri"
                try {
                    onProgress(AnalysisStage.DECODE, 0.25f)
                    delay(durations[uri] ?: DEFAULT_MS)
                    if (uri in failing) error("boom $uri")
                    onProgress(AnalysisStage.KEY, 0.75f)
                    log += "done:$uri"
                    if (persist) stored += id
                } catch (e: CancellationException) {
                    log += "cancel:$uri"
                    throw e
                }
            },
            reanalyze = { uri, id, onProgress ->
                log += "restart:$uri"
                try {
                    onProgress(AnalysisStage.DECODE, 0.5f)
                    delay(durations[uri] ?: DEFAULT_MS)
                    if (uri in failing) throw failures[uri] ?: IllegalStateException("boom $uri")
                    log += "redone:$uri"
                    stored += id
                } catch (e: CancellationException) {
                    log += "cancel:$uri"
                    throw e
                }
            },
            scope = scope,
            upNextLimit = upNextLimit,
            trace = { traces += it },
            monotonicNanos = { test.testScheduler.currentTime * 1_000_000L },
        )

        fun set(
            playing: String?,
            upNext: List<String> = emptyList(),
            djOn: Boolean = true,
            load: DeviceLoad = DeviceLoad(),
        ) {
            input.value = SchedulingInput(djOn, playing, upNext, load)
        }

        /** Runs the virtual clock up to absolute time [ms] and every task due then. */
        fun at(ms: Long) {
            testScope.advanceTimeBy(ms - testScope.currentTime)
            testScope.runCurrent()
        }

        fun status(uri: String): AnalysisStatus? = queue.status.value[uri]

        /** The decisions as `uri decision elapsedMs [detail]`, in the order they were traced. */
        fun decisions(): List<String> = traces.filterIsInstance<ScheduleTrace.Decision>().map { d ->
            listOfNotNull(d.uri, d.decision.name.lowercase(), d.elapsedMs.toString(), d.detail).joinToString(" ")
        }

        fun analysed(): List<String> = log.filter { it.startsWith("start:") }.map { it.removePrefix("start:") }

        /** The uris whose analysis was run again on request, in order. */
        fun reanalysed(): List<String> = log.filter { it.startsWith("restart:") }.map { it.removePrefix("restart:") }

        companion object {
            const val DEFAULT_MS = 100L
        }
    }

    private fun TestScope.harness(upNextLimit: Int = 3, idOf: (String) -> AudioId = ::idOf) =
        Harness(this, backgroundScope, upNextLimit, idOf)

    @Test
    fun `the playing track is analysed first and the up next follow in queue order`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(1000)
        assertThat(h.analysed()).containsExactly("A", "B", "C").inOrder()
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("C")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `only one analysis runs at a time`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"))
        h.at(50)
        assertThat(h.status("A")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        assertThat(h.analysed()).containsExactly("A")
    }

    @Test
    fun `up next is looked ahead only up to the limit`() = runTest {
        val h = harness(upNextLimit = 2)
        h.set(playing = "A", upNext = listOf("B", "C", "D", "E"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B", "C").inOrder()
        assertThat(h.status("D")).isNull()
        assertThat(h.status("E")).isNull()
    }

    @Test
    fun `a limit of zero analyses only the playing track`() = runTest {
        val h = harness(upNextLimit = 0)
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A")
        assertThat(h.status("B")).isNull()
    }

    @Test
    fun `a negative limit is rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { harness(upNextLimit = -1) }
    }

    @Test
    fun `nothing is analysed while DJ mode is off`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"), djOn = false)
        h.at(5000)
        assertThat(h.log).isEmpty()
        assertThat(h.queue.status.value).isEmpty()
    }

    @Test
    fun `a new playing track pre-empts a running background analysis which resumes afterwards`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.durations["X"] = 200
        h.set(playing = "A", upNext = listOf("B"))
        h.at(100) // A done, B running
        assertThat(h.status("B")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        h.at(150)
        h.set(playing = "X", upNext = listOf("B"))
        h.at(150)
        assertThat(h.log.filter { it.startsWith("start:") || it.startsWith("cancel:") }.takeLast(2))
            .containsExactly("cancel:B", "start:X").inOrder()
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        h.at(2000)
        assertThat(h.log.filter { it.startsWith("start:") || it.startsWith("done:") || it.startsWith("cancel:") })
            .containsExactly(
                "start:A", "done:A", "start:B", "cancel:B", "start:X", "done:X", "start:B", "done:B",
            ).inOrder()
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("X")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `an up next track that becomes the playing one keeps running without a restart`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300) // B running since 100
        h.set(playing = "B", upNext = emptyList())
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B").inOrder()
        assertThat(h.log).doesNotContain("cancel:B")
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a playing track that turns out to be analysed costs no analysis and the background work restarts`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.stored += idOf("X")
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300)
        // An unknown playing track pre-empts first (only identifying it tells it is analysed); B restarts after.
        h.set(playing = "X", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B", "B").inOrder()
        assertThat(h.status("X")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a running analysis whose track left the queue is cancelled and forgotten`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(300)
        h.set(playing = "A", upNext = listOf("C"))
        h.at(310)
        assertThat(h.log).contains("cancel:B")
        assertThat(h.status("B")).isNull()
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B", "C").inOrder() // B once, started before it was dropped
        assertThat(h.status("C")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `turning DJ mode off cancels the running analysis and clears the pending ones`() = runTest {
        val h = harness()
        h.durations["A"] = 1000
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(300)
        h.set(playing = "A", upNext = listOf("B", "C"), djOn = false)
        h.at(310)
        assertThat(h.log).contains("cancel:A")
        assertThat(h.queue.status.value).isEmpty()
        h.at(10_000)
        assertThat(h.analysed()).containsExactly("A")
    }

    @Test
    fun `turning DJ mode back on resumes the work and keeps what was analysed`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"))
        h.at(150) // A done, B running
        h.set(playing = "A", upNext = listOf("B"), djOn = false)
        h.at(160)
        h.set(playing = "A", upNext = listOf("B"), djOn = true)
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B", "B").inOrder()
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `battery saver pauses the background items but not the playing track`() = runTest {
        val h = harness()
        val saver = DeviceLoad(batterySaver = true)
        h.set(playing = "A", upNext = listOf("B", "C"), load = saver)
        h.at(1000)
        assertThat(h.analysed()).containsExactly("A")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        h.set(playing = "X", upNext = listOf("B", "C"), load = saver) // a new playing track still runs
        h.at(2000)
        assertThat(h.analysed()).containsExactly("A", "X").inOrder()
        h.set(playing = "X", upNext = listOf("B", "C"), load = DeviceLoad())
        h.at(4000)
        assertThat(h.analysed()).containsExactly("A", "X", "B", "C").inOrder()
    }

    @Test
    fun `a severe thermal state pauses a running background analysis and resumes it when it cools`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300) // B running
        h.set(playing = "A", upNext = listOf("B"), load = DeviceLoad(thermalThrottled = true))
        h.at(310)
        assertThat(h.log).contains("cancel:B")
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B")
        h.set(playing = "A", upNext = listOf("B"), load = DeviceLoad())
        h.at(7000)
        assertThat(h.analysed()).containsExactly("A", "B", "B").inOrder()
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `the playing track runs under battery saver even when it was running in the background`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300) // B running in the background
        h.set(playing = "B", upNext = emptyList(), load = DeviceLoad(batterySaver = true))
        h.at(5000)
        assertThat(h.log).doesNotContain("cancel:B")
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `an already analysed track is skipped and reported analysed`() = runTest {
        val h = harness()
        h.stored += idOf("B")
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "C").inOrder()
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `an analysed playing track costs no analysis`() = runTest {
        val h = harness()
        h.stored += idOf("A")
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("B")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a failure is reported and not retried in a loop`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Failed("boom A"))
        assertThat(h.analysed()).containsExactly("A", "B").inOrder()
        // Further scheduling inputs do not retry it.
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(10_000)
        h.set(playing = "A", upNext = listOf("C"), load = DeviceLoad(batterySaver = true))
        h.at(15_000)
        assertThat(h.analysed().count { it == "A" }).isEqualTo(1)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Failed("boom A"))
    }

    @Test
    fun `a failed background track is retried only when it becomes the playing one again`() = runTest {
        val h = harness()
        h.failing += "B"
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Failed("boom B"))
        h.failing.clear()
        h.set(playing = "B", upNext = emptyList())
        h.at(6000)
        assertThat(h.analysed()).containsExactly("A", "B", "B").inOrder()
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a track that fails while playing is not retried until it is played again`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A")
        h.at(1000)
        h.set(playing = "A", upNext = listOf("B")) // same playing track: no retry
        h.at(3000)
        assertThat(h.analysed().count { it == "A" }).isEqualTo(1)
        h.set(playing = "B")
        h.at(4000)
        h.failing.clear()
        h.set(playing = "A") // played again: one retry
        h.at(6000)
        assertThat(h.analysed().count { it == "A" }).isEqualTo(2)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a track that cannot be identified is reported failed`() = runTest {
        val h = harness()
        h.identifyFailing += "A"
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Failed("unreadable A"))
        assertThat(h.analysed()).containsExactly("B")
        assertThat(h.log.count { it == "identify:A" }).isEqualTo(1)
    }

    @Test
    fun `duplicate uris are analysed once`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("A", "B", "B", "A"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `duplicates do not use up the look ahead`() = runTest {
        val h = harness(upNextLimit = 2)
        h.set(playing = "A", upNext = listOf("A", "B", "B", "C", "D"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun `two uris with the same audio identity are analysed once`() = runTest {
        val h = harness(idOf = { uri -> idOf(uri.removeSuffix("-copy")) })
        h.persist = false // only the scheduler's own memory can prevent the second analysis
        h.set(playing = "A", upNext = listOf("A-copy", "B"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A", "B").inOrder()
        assertThat(h.status("A-copy")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a failed audio identity is not retried through another uri`() = runTest {
        val h = harness(idOf = { uri -> idOf(uri.removeSuffix("-copy")) })
        h.failing += "A"
        h.set(playing = "A", upNext = listOf("A-copy"))
        h.at(5000)
        assertThat(h.analysed()).containsExactly("A")
        assertThat(h.status("A-copy")).isEqualTo(AnalysisStatus.Failed("boom A"))
    }

    @Test
    fun `an identity is not computed again when a cancelled analysis resumes`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.durations["X"] = 100
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300)
        h.set(playing = "X", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.log.count { it == "identify:B" }).isEqualTo(1)
        assertThat(h.analysed()).containsExactly("A", "B", "X", "B").inOrder()
    }

    @Test
    fun `the status goes from queued to analysing with progress to analysed`() = runTest {
        val h = harness()
        val seen = mutableListOf<AnalysisStatus?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.queue.status.collect { map ->
                val now = map["A"]
                if (seen.isEmpty() || seen.last() != now) seen += now
            }
        }
        h.set(playing = "A")
        h.at(1000)
        assertThat(seen).containsExactly(
            null,
            AnalysisStatus.Queued,
            AnalysisStatus.Analysing(AnalysisStage.IDENTIFY, 0f),
            AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.25f),
            AnalysisStatus.Analysing(AnalysisStage.KEY, 0.75f),
            AnalysisStatus.Analysed,
        ).inOrder()
    }

    @Test
    fun `a progress report after a cancellation does not overwrite the queued status`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300)
        h.set(playing = "X", upNext = listOf("B"))
        h.at(310)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        assertThat(h.status("X")).isInstanceOf(AnalysisStatus.Analysing::class.java)
    }

    @Test
    fun `every input is traced while DJ mode is on`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B", "C"), load = DeviceLoad(thermalThrottled = true))
        h.at(0)
        assertThat(h.traces.first()).isEqualTo(
            ScheduleTrace.InputChanged(SchedulingInput(true, "A", listOf("B", "C"), DeviceLoad(thermalThrottled = true))),
        )
    }

    @Test
    fun `the decisions are traced with the time since the input that caused them`() = runTest {
        val h = harness()
        h.stored += idOf("B")
        h.identifyDelays["B"] = 150
        h.at(1000)
        h.set(playing = "A", upNext = listOf("B", "C"))
        h.at(5000)
        assertThat(h.decisions()).containsExactly(
            "A queued 0",
            "B queued 0",
            "C queued 0",
            "A identifying 0",
            "A started 0",
            "B identifying 100",
            "B skipped_analysed 250",
            "C identifying 250",
            "C started 250",
        ).inOrder()
        val started = h.traces.filterIsInstance<ScheduleTrace.Decision>().first { it.uri == "C" && it.decision == ScheduleDecision.STARTED }
        assertThat(started.audioId).isEqualTo(idOf("C"))
    }

    @Test
    fun `a track appended later is timed from its own input`() = runTest {
        val h = harness()
        h.stored += idOf("A")
        h.set(playing = "A")
        h.at(1000)
        h.traces.clear()
        h.set(playing = "A", upNext = listOf("B"))
        h.at(3000)
        assertThat(h.decisions()).containsExactly("B queued 0", "B identifying 0", "B started 0").inOrder()
    }

    @Test
    fun `background items held by the device load are traced as held`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"), load = DeviceLoad(batterySaver = true))
        h.at(1000)
        assertThat(h.decisions()).containsAtLeast("A queued 0", "B queued 0 held_by_load").inOrder()
        assertThat(h.decisions()).doesNotContain("B identifying 0")
    }

    @Test
    fun `cancellations are traced with their reason`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.durations["C"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(300) // B running
        h.set(playing = "A", upNext = listOf("B"), load = DeviceLoad(thermalThrottled = true))
        h.at(400)
        h.set(playing = "A", upNext = listOf("B"))
        h.at(500) // B running again
        h.set(playing = "A", upNext = listOf("C"))
        h.at(600) // C running
        h.set(playing = "X", upNext = listOf("C"))
        h.at(650)
        assertThat(h.decisions()).containsAtLeast(
            "B cancelled 0 load",
            "B started 0",
            "B cancelled 0 left_window",
            "C cancelled 0 preempted",
        ).inOrder()
    }

    @Test
    fun `an identification failure is traced`() = runTest {
        val h = harness()
        h.identifyFailing += "A"
        h.set(playing = "A")
        h.at(1000)
        assertThat(h.decisions()).contains("A identify_failed 0 unreadable A")
    }

    @Test
    fun `nothing is traced while DJ mode is off, nor when turning it off`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"), djOn = false)
        h.at(1000)
        assertThat(h.traces).isEmpty()
        h.set(playing = "A", upNext = listOf("B"))
        h.at(1050) // A running
        h.traces.clear()
        h.set(playing = "A", upNext = listOf("B"), djOn = false)
        h.at(5000)
        assertThat(h.traces).isEmpty()
    }

    // --- Retry and re-analysis (D2.11) -------------------------------------------------------------------------------

    @Test
    fun `re-analysing an analysed playing track runs the replacing analysis and reports it as replacing`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        h.queue.reanalyse("A")
        h.at(1050)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.5f, replacing = true))
        h.at(2000)
        assertThat(h.reanalysed()).containsExactly("A")
        assertThat(h.analysed()).containsExactly("A")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a re-analysis runs once and later inputs do not repeat it`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        h.queue.reanalyse("A")
        h.at(2000)
        h.set(playing = "A", upNext = listOf("B"))
        h.at(4000)
        h.set(playing = "A", upNext = listOf("B"), load = DeviceLoad(batterySaver = true))
        h.at(6000)
        assertThat(h.reanalysed()).containsExactly("A")
    }

    @Test
    fun `a re-analysis of the playing track pre-empts a background analysis, which then resumes`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(500)
        assertThat(h.status("B")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        h.queue.reanalyse("A")
        h.at(520)
        assertThat(h.log).contains("cancel:B")
        assertThat(h.status("A")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        h.at(5000)
        assertThat(h.reanalysed()).containsExactly("A")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
        val cancelled = h.traces.filterIsInstance<ScheduleTrace.Decision>().filter { it.decision == ScheduleDecision.CANCELLED }
        assertThat(cancelled.map { it.detail }).containsExactly(ScheduleTrace.PREEMPTED)
    }

    @Test
    fun `a re-analysis of a track whose first analysis is running restarts it as a replacing one`() = runTest {
        val h = harness()
        h.durations["A"] = 1000
        h.set(playing = "A")
        h.at(300)
        h.queue.reanalyse("A")
        h.at(320)
        assertThat(h.log).contains("cancel:A")
        h.at(5000)
        assertThat(h.reanalysed()).containsExactly("A")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `asking again while the re-analysis runs does not start another one`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        h.queue.reanalyse("A")
        h.at(1020)
        h.queue.reanalyse("A")
        h.queue.reanalyse("A")
        h.at(5000)
        assertThat(h.reanalysed()).containsExactly("A")
        assertThat(h.log.count { it == "cancel:A" }).isEqualTo(0)
    }

    @Test
    fun `a re-analysis outside the window or with DJ mode off is ignored`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        h.queue.reanalyse("Z")
        h.at(2000)
        assertThat(h.reanalysed()).isEmpty()
        assertThat(h.status("Z")).isNull()
        h.set(playing = "A", djOn = false)
        h.at(3000)
        h.queue.reanalyse("A")
        h.at(4000)
        assertThat(h.reanalysed()).isEmpty()
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a re-analysis of an up next track waits behind the playing track`() = runTest {
        val h = harness()
        h.set(playing = "A", upNext = listOf("B"))
        h.at(1000)
        h.set(playing = "A", upNext = listOf("B"))
        h.durations["A"] = 1000
        h.queue.reanalyse("A")
        h.queue.reanalyse("B")
        h.at(1050)
        assertThat(h.status("A")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Queued)
        h.at(5000)
        assertThat(h.reanalysed()).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `a re-analysis keeps its mark when it is pre-empted`() = runTest {
        val h = harness()
        h.durations["B"] = 1000
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        h.queue.reanalyse("B")
        h.at(5200)
        assertThat(h.status("B")).isInstanceOf(AnalysisStatus.Analysing::class.java)
        h.set(playing = "X", upNext = listOf("B"))
        h.at(5250)
        assertThat(h.log).contains("cancel:B")
        h.at(20_000)
        // The cancelled run started once and the resumed one finished: the mark survived the pre-emption.
        assertThat(h.log.count { it == "restart:B" }).isEqualTo(2)
        assertThat(h.log.count { it == "redone:B" }).isEqualTo(1)
        assertThat(h.status("B")).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `a re-analysis that fails is reported failed with its kind and is not repeated`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        h.failing += "A"
        h.failures["A"] = PcmDecodeException.CorruptData("truncated")
        h.queue.reanalyse("A")
        h.at(3000)
        assertThat(h.status("A")).isEqualTo(
            AnalysisStatus.Failed("Corrupt audio data: truncated", AnalysisFailureKind.CORRUPT),
        )
        h.set(playing = "A", upNext = listOf("B"))
        h.at(6000)
        assertThat(h.reanalysed()).containsExactly("A")
    }

    @Test
    fun `a failed track can be re-analysed and its failure is forgotten`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A")
        h.at(1000)
        assertThat(h.status("A")).isInstanceOf(AnalysisStatus.Failed::class.java)
        h.failing.clear()
        h.queue.reanalyse("A")
        h.at(2000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.reanalysed()).containsExactly("A")
    }

    @Test
    fun `retry gives a failed playing track one new attempt without replacing anything`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A", upNext = listOf("B"))
        h.at(5000)
        assertThat(h.status("A")).isInstanceOf(AnalysisStatus.Failed::class.java)
        h.failing.clear()
        h.queue.retry("A")
        h.at(6000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Analysed)
        assertThat(h.analysed()).containsExactly("A", "B", "A").inOrder()
        assertThat(h.reanalysed()).isEmpty()
    }

    @Test
    fun `retry of a track that fails again reports the failure again`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A")
        h.at(1000)
        h.queue.retry("A")
        h.at(2000)
        assertThat(h.analysed()).containsExactly("A", "A")
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Failed("boom A"))
    }

    @Test
    fun `retry of a track that is not failed or not in the window does nothing`() = runTest {
        val h = harness()
        h.set(playing = "A")
        h.at(1000)
        h.queue.retry("A")
        h.queue.retry("Z")
        h.at(3000)
        assertThat(h.analysed()).containsExactly("A")
        assertThat(h.status("Z")).isNull()
    }

    @Test
    fun `a failure to identify keeps its kind`() = runTest {
        val h = harness()
        h.identifyFailing += "A"
        h.failures["A"] = FileNotFoundException("gone")
        h.set(playing = "A")
        h.at(1000)
        assertThat(h.status("A")).isEqualTo(AnalysisStatus.Failed("gone", AnalysisFailureKind.UNREADABLE))
    }

    @Test
    fun `a failed analysis keeps its kind`() = runTest {
        val h = harness()
        h.failing += "A"
        h.set(playing = "A")
        h.at(1000)
        assertThat((h.status("A") as AnalysisStatus.Failed).kind).isEqualTo(AnalysisFailureKind.OTHER)
    }
}
