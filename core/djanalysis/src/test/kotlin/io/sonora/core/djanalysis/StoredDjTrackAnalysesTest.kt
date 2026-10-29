package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.ByteSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.FileStamp
import io.sonora.core.djmodel.GridEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoredDjTrackAnalysesTest {

    private class FakeStatus : DjAnalysisStatusSource {
        val flow = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
        override val status: StateFlow<Map<String, AnalysisStatus>> get() = flow
    }

    /** The documents: only the stamp may be asked; opening a file (which only a hasher does) is a failure. */
    private class FakeDocuments : AudioDocuments {
        val stamps = HashMap<String, FileStamp?>()
        var stampCalls = 0
        var failure: Exception? = null

        override fun stamp(sourceUri: String): FileStamp? {
            stampCalls++
            failure?.let { throw it }
            return stamps[sourceUri]
        }

        override fun open(sourceUri: String): ByteSource = throw AssertionError("The analysis view must never read the file")
    }

    /** Behaves like the real index (a given stamp must match the remembered one) and records what it was asked. */
    private class FakeIndex : AudioIdIndex {
        val memo = HashMap<String, Pair<AudioId, FileStamp?>>()
        val asked = ArrayList<Pair<String, FileStamp?>>()
        var gate: CompletableDeferred<Unit>? = null
        var lookupCancelled = false

        override suspend fun idFor(sourceUri: String, stamp: FileStamp?): AudioId? {
            asked += sourceUri to stamp
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                lookupCancelled = true
                throw e
            }
            val (id, remembered) = memo[sourceUri] ?: return null
            return if (stamp == null || stamp == remembered) id else null
        }

        override suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp?) =
            throw AssertionError("The analysis view must never remember an id")
    }

    private class FakeStore : DjAnalysisStore {
        private val flows = HashMap<AudioId, MutableStateFlow<DjAnalysis?>>()
        val observing = HashSet<AudioId>()

        fun set(id: AudioId, analysis: DjAnalysis?) {
            flows.getOrPut(id) { MutableStateFlow(null) }.value = analysis
        }

        override fun observe(id: AudioId): Flow<DjAnalysis?> =
            flows.getOrPut(id) { MutableStateFlow(null) }.onStart { observing += id }.onCompletion { observing -= id }

        override suspend fun get(id: AudioId): DjAnalysis? = throw AssertionError("Only observe is used")
        override suspend fun analysedAmong(ids: Collection<AudioId>): Set<AudioId> = throw AssertionError("Only observe is used")
        override suspend fun putIfAbsent(id: AudioId, analysis: DjAnalysis): Boolean = throw AssertionError("Only observe is used")
        override suspend fun replace(id: AudioId, analysis: DjAnalysis) = throw AssertionError("Only observe is used")
    }

    private val status = FakeStatus()
    private val documents = FakeDocuments()
    private val index = FakeIndex()
    private val store = FakeStore()
    private val edits = FakeGridEditStore()
    private val uri = "content://tracks/a"
    private val stamp = FileStamp(sizeBytes = 5_000_000, lastModifiedMs = 1_700_000_000_000)
    private val first = analysis(durationMs = 1_000)

    private fun TestScope.analyses() =
        StoredDjTrackAnalyses(status, documents, index, store, edits, UnconfinedTestDispatcher(testScheduler))

    /** Collects [flow] into [values] on a background coroutine of this test. */
    private fun TestScope.collect(flow: Flow<DjAnalysis?>, values: MutableList<DjAnalysis?>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { values += it } }

    @Test
    fun `a remembered id observes the stored analysis`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(values).containsExactly(first)
    }

    @Test
    fun `the lookup is made with the stamp the provider reports now`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp

        collect(analyses().analysisOf(uri), ArrayList())

        assertThat(index.asked).containsExactly(uri to stamp)
    }

    @Test
    fun `a file that changed since it was identified has no analysis`() = runTest {
        documents.stamps[uri] = FileStamp(sizeBytes = 5_000_001, lastModifiedMs = stamp.lastModifiedMs)
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(values).containsExactly(null)
        assertThat(store.observing).isEmpty()
    }

    @Test
    fun `a provider without a stamp asks for the id as remembered`() = runTest {
        documents.stamps[uri] = null
        index.memo[uri] = audioId(1) to null
        store.set(audioId(1), first)
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(index.asked).containsExactly(uri to null)
        assertThat(values).containsExactly(first)
    }

    @Test
    fun `an unknown track has no analysis and the file is never read`() = runTest {
        documents.stamps[uri] = stamp
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(values).containsExactly(null)
        // FakeDocuments.open and FakeIndex.remember throw AssertionError if reached.
    }

    @Test
    fun `a miss is retried when the status of the track changes`() = runTest {
        documents.stamps[uri] = stamp
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)
        assertThat(values).containsExactly(null)

        // The scheduler identifies the file and remembers its id, then reports the next stage.
        index.memo[uri] = audioId(1) to stamp
        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.0f))
        assertThat(store.observing).containsExactly(audioId(1))

        store.set(audioId(1), first)
        assertThat(values.last()).isEqualTo(first)
    }

    @Test
    fun `progress ticks inside one stage do not look the id up again`() = runTest {
        documents.stamps[uri] = stamp
        collect(analyses().analysisOf(uri), ArrayList())
        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.1f))
        val calls = documents.stampCalls

        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.2f))
        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.3f))

        assertThat(documents.stampCalls).isEqualTo(calls)
    }

    @Test
    fun `a new stage and the end of the analysis look the id up again`() = runTest {
        documents.stamps[uri] = stamp
        collect(analyses().analysisOf(uri), ArrayList())
        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.1f))
        val calls = documents.stampCalls

        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.MEL, 0.0f))
        assertThat(documents.stampCalls).isEqualTo(calls + 1)

        status.flow.value = mapOf(uri to AnalysisStatus.Analysed)
        assertThat(documents.stampCalls).isEqualTo(calls + 2)
    }

    @Test
    fun `the status of other tracks does not look anything up`() = runTest {
        documents.stamps[uri] = stamp
        collect(analyses().analysisOf(uri), ArrayList())
        val calls = documents.stampCalls

        status.flow.value = mapOf("content://tracks/other" to AnalysisStatus.Analysed)
        status.flow.value = mapOf("content://tracks/other" to AnalysisStatus.Queued)

        assertThat(documents.stampCalls).isEqualTo(calls)
    }

    @Test
    fun `a changed id switches to the new analysis and stops observing the old one`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        val second = analysis(durationMs = 2_000)
        store.set(audioId(1), first)
        store.set(audioId(2), second)
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)

        index.memo[uri] = audioId(2) to stamp
        status.flow.value = mapOf(uri to AnalysisStatus.Analysed)

        assertThat(values.last()).isEqualTo(second)
        assertThat(store.observing).containsExactly(audioId(2))
    }

    @Test
    fun `a change of the stored analysis is emitted`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        val restored = analysis(waveform = BandWaveform.MISSING)
        store.set(audioId(1), restored)
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)

        store.set(audioId(1), first)

        assertThat(values).containsExactly(restored, first).inOrder()
    }

    @Test
    fun `cancelling the collection stops observing and ignores later status changes`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        val job = collect(analyses().analysisOf(uri), ArrayList())
        assertThat(store.observing).containsExactly(audioId(1))
        val calls = documents.stampCalls

        job.cancel()
        status.flow.value = mapOf(uri to AnalysisStatus.Analysed)

        assertThat(store.observing).isEmpty()
        assertThat(documents.stampCalls).isEqualTo(calls)
    }

    @Test
    fun `cancelling during a lookup cancels the lookup`() = runTest {
        documents.stamps[uri] = stamp
        index.gate = CompletableDeferred()
        val job = collect(analyses().analysisOf(uri), ArrayList())
        assertThat(index.asked).hasSize(1)

        job.cancel()

        assertThat(index.lookupCancelled).isTrue()
    }

    @Test
    fun `a newer status cancels a lookup still running`() = runTest {
        documents.stamps[uri] = stamp
        index.gate = CompletableDeferred()
        collect(analyses().analysisOf(uri), ArrayList())

        status.flow.value = mapOf(uri to AnalysisStatus.Queued)

        assertThat(index.lookupCancelled).isTrue()
        assertThat(index.asked).hasSize(2)
    }

    @Test
    fun `a provider that fails the stamp gives no analysis and is tried again later`() = runTest {
        documents.failure = SecurityException("access revoked")
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)
        assertThat(values).containsExactly(null)

        documents.failure = null
        documents.stamps[uri] = stamp
        status.flow.value = mapOf(uri to AnalysisStatus.Analysed)

        assertThat(values.last()).isEqualTo(first)
    }

    @Test
    fun `two tracks are followed independently`() = runTest {
        val other = "content://tracks/b"
        documents.stamps[uri] = stamp
        documents.stamps[other] = stamp
        index.memo[uri] = audioId(1) to stamp
        index.memo[other] = audioId(2) to stamp
        val second = analysis(durationMs = 2_000)
        store.set(audioId(1), first)
        store.set(audioId(2), second)
        val a = ArrayList<DjAnalysis?>()
        val b = ArrayList<DjAnalysis?>()
        val analyses = analyses()

        collect(analyses.analysisOf(uri), a)
        collect(analyses.analysisOf(other), b)

        assertThat(a).containsExactly(first)
        assertThat(b).containsExactly(second)
    }

    @Test
    fun `an analysis carries the grid edit stored for the same audio`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        edits.set(audioId(1), GridEdit(12_800, 300.0))
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(values.last()).isEqualTo(first.withGridEdit(GridEdit(12_800, 300.0)))
        assertThat(values.last()!!.effectiveGrid!!.bpmAt(0.0)).isEqualTo(128.0)
        assertThat(values.last()!!.grid).isEqualTo(first.grid)
    }

    @Test
    fun `a re-analysed track keeps showing the grid edit over the new analysis`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        edits.set(audioId(1), GridEdit(12_800, 300.0))
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)
        val replaced = analysis(durationMs = 2_000, grid = null)

        store.set(audioId(1), replaced)

        val shown = values.last()!!
        assertThat(shown.durationMs).isEqualTo(2_000)
        assertThat(shown.grid).isNull()
        assertThat(shown.gridEdit).isEqualTo(GridEdit(12_800, 300.0))
        assertThat(shown.effectiveGrid!!.bpmAt(0.0)).isEqualTo(128.0)
    }

    @Test
    fun `an edit saved or deleted later reaches the screen at once`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)
        val values = ArrayList<DjAnalysis?>()
        collect(analyses().analysisOf(uri), values)
        assertThat(values.last()!!.gridEdit).isNull()

        edits.set(audioId(1), GridEdit(12_000, 100.0))
        assertThat(values.last()!!.gridEdit).isEqualTo(GridEdit(12_000, 100.0))

        edits.set(audioId(1), null)
        assertThat(values.last()!!.gridEdit).isNull()
        assertThat(values.last()).isEqualTo(first)
    }

    @Test
    fun `an edit without an analysis is no analysis`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        edits.set(audioId(1), GridEdit(12_000, 100.0))
        val values = ArrayList<DjAnalysis?>()

        collect(analyses().analysisOf(uri), values)

        assertThat(values).containsExactly(null)
    }

    @Test
    fun `the edits are observed only for the track being shown`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.set(audioId(1), first)

        val job = collect(analyses().analysisOf(uri), ArrayList())
        assertThat(edits.observing).containsExactly(audioId(1))

        job.cancel()
        assertThat(edits.observing).isEmpty()
    }
}
