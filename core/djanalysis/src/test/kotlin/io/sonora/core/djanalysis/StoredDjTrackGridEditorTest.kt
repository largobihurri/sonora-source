package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.ByteSource
import io.sonora.core.djmodel.FileStamp
import io.sonora.core.djmodel.GridEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoredDjTrackGridEditorTest {

    private class Documents : AudioDocuments {
        val stamps = HashMap<String, FileStamp?>()
        var failure: Exception? = null

        override fun stamp(sourceUri: String): FileStamp? {
            failure?.let { throw it }
            return stamps[sourceUri]
        }

        override fun open(sourceUri: String): ByteSource = throw AssertionError("The editor must never read the file")
    }

    private class Index : AudioIdIndex {
        val memo = HashMap<String, Pair<AudioId, FileStamp?>>()

        override suspend fun idFor(sourceUri: String, stamp: FileStamp?): AudioId? {
            val (id, remembered) = memo[sourceUri] ?: return null
            return if (stamp == null || stamp == remembered) id else null
        }

        override suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp?) = throw AssertionError("The editor must never remember an id")
    }

    private val documents = Documents()
    private val index = Index()
    private val edits = FakeGridEditStore()
    private val uri = "content://tracks/a"
    private val stamp = FileStamp(sizeBytes = 5_000_000, lastModifiedMs = 1_700_000_000_000)

    private fun kotlinx.coroutines.test.TestScope.editor() =
        StoredDjTrackGridEditor(documents, index, edits, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `an identified track gets its edit stored under its audio id`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp

        val saved = editor().save(uri, GridEdit(12_800, 300.0))

        assertThat(saved).isTrue()
        assertThat(edits.writes).containsExactly(audioId(1) to GridEdit(12_800, 300.0))
    }

    @Test
    fun `a track that is not identified stores nothing`() = runTest {
        documents.stamps[uri] = stamp

        assertThat(editor().save(uri, GridEdit(12_800, 300.0))).isFalse()
        assertThat(editor().reset(uri)).isFalse()
        assertThat(edits.writes).isEmpty()
    }

    @Test
    fun `a file that changed since it was identified stores nothing`() = runTest {
        documents.stamps[uri] = FileStamp(sizeBytes = 1, lastModifiedMs = 2)
        index.memo[uri] = audioId(1) to stamp

        assertThat(editor().save(uri, GridEdit(12_800, 300.0))).isFalse()
        assertThat(edits.writes).isEmpty()
    }

    @Test
    fun `a provider that cannot answer stores nothing and does not fail`() = runTest {
        documents.failure = SecurityException("access revoked")
        index.memo[uri] = audioId(1) to stamp

        assertThat(editor().save(uri, GridEdit(12_800, 300.0))).isFalse()
        assertThat(edits.writes).isEmpty()
    }

    @Test
    fun `a reset deletes the edit of the track`() = runTest {
        documents.stamps[uri] = stamp
        index.memo[uri] = audioId(1) to stamp
        edits.set(audioId(1), GridEdit(12_800, 300.0))

        assertThat(editor().reset(uri)).isTrue()

        assertThat(edits.writes).containsExactly(audioId(1) to null)
        assertThat(edits.get(audioId(1))).isNull()
    }
}
