package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.ByteSource
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.FileStamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoredDjTrackCuesTest {

    private class FakeStatus : DjAnalysisStatusSource {
        val flow = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
        override val status: StateFlow<Map<String, AnalysisStatus>> get() = flow
    }

    private class Documents : AudioDocuments {
        val stamps = HashMap<String, FileStamp?>()
        var failure: Exception? = null

        override fun stamp(sourceUri: String): FileStamp? {
            failure?.let { throw it }
            return stamps[sourceUri]
        }

        override fun open(sourceUri: String): ByteSource = throw AssertionError("The cues must never read the file")
    }

    private class Index : AudioIdIndex {
        val memo = HashMap<String, Pair<AudioId, FileStamp?>>()

        override suspend fun idFor(sourceUri: String, stamp: FileStamp?): AudioId? {
            val (id, remembered) = memo[sourceUri] ?: return null
            return if (stamp == null || stamp == remembered) id else null
        }

        override suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp?) = throw AssertionError("The cues must never remember an id")
    }

    private val status = FakeStatus()
    private val documents = Documents()
    private val index = Index()
    private val store = FakeCueStore()
    private val uri = "content://tracks/a"
    private val stamp = FileStamp(sizeBytes = 5_000_000, lastModifiedMs = 1_700_000_000_000)
    private val cue = DjCue("hot-0", CueKind.HOT, 0, 1_000.0, null, CuePalette.defaultHot(0), "")

    private fun TestScope.cues() = StoredDjTrackCues(status, documents, index, store, UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.collect(into: MutableList<List<DjCue>>, flow: kotlinx.coroutines.flow.Flow<List<DjCue>>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { into += it } }

    @Test
    fun `an identified track shows the cues of its audio id and follows their changes`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.put(audioId(1), cue)
        store.writes.clear()
        val seen = ArrayList<List<DjCue>>()

        collect(seen, cues().cuesOf(uri))
        val second = DjCue("m1", CueKind.MEMORY, null, 5_000.0, null, CuePalette.MEMORY_DEFAULT, "")
        store.put(audioId(1), second)

        assertThat(seen).containsExactly(listOf(cue), listOf(cue, second)).inOrder()
    }

    @Test
    fun `a track that is not identified has no cues, and gets them once it is`() = runTest {
        documents.stamps[uri] = stamp
        store.put(audioId(1), cue)
        val seen = ArrayList<List<DjCue>>()

        collect(seen, cues().cuesOf(uri))
        assertThat(seen.last()).isEmpty()
        assertThat(store.observing).isEmpty()

        index.memo[uri] = audioId(1) to stamp
        status.flow.value = mapOf(uri to AnalysisStatus.Analysing(AnalysisStage.GRID, 0.1f))

        assertThat(seen.last()).containsExactly(cue)
        assertThat(store.observing).containsExactly(audioId(1))
    }

    @Test
    fun `a file that changed since it was identified has no cues`() = runTest {
        documents.stamps[uri] = FileStamp(sizeBytes = 1, lastModifiedMs = 2)
        index.memo[uri] = audioId(1) to stamp
        store.put(audioId(1), cue)
        val seen = ArrayList<List<DjCue>>()

        collect(seen, cues().cuesOf(uri))

        assertThat(seen.single()).isEmpty()
    }

    @Test
    fun `saving stores the cue under the audio id of the track`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp

        assertThat(cues().save(uri, cue)).isTrue()

        assertThat(store.writes).containsExactly(Triple(audioId(1), "hot-0", cue))
    }

    @Test
    fun `a track that is not identified stores nothing`() = runTest {
        documents.stamps[uri] = stamp

        assertThat(cues().save(uri, cue)).isFalse()
        assertThat(cues().delete(uri, "hot-0")).isFalse()
        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `a provider that cannot answer stores nothing and does not fail`() = runTest {
        documents.failure = SecurityException("access revoked")
        index.memo[uri] = audioId(1) to stamp

        assertThat(cues().save(uri, cue)).isFalse()
        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `deleting removes only that cue of the track`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        store.put(audioId(1), cue)
        store.writes.clear()

        assertThat(cues().delete(uri, "hot-0")).isTrue()

        assertThat(store.writes).containsExactly(Triple(audioId(1), "hot-0", null))
        assertThat(store.get(audioId(1))).isEmpty()
    }
}
