package io.sonora.core.domain.library.removal

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.FakeOutputRoot
import io.sonora.core.domain.conversion.batch.FakeRepository
import io.sonora.core.domain.conversion.batch.newBatch
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ConverterFolderAccessTest {

    private val outputRoot = FakeOutputRoot(null)
    private val batches = FakeRepository()
    private val access = ConverterFolderAccess(outputRoot, batches)

    private fun tree(id: String) = "content://com.android.externalstorage.documents/tree/primary%3A$id"

    private fun document(tree: String, path: String) = "$tree/document/primary%3A" + path.replace("/", "%2F")

    @Test
    fun `nothing in the converter needs a folder it never saw`() = runBlocking<Unit> {
        assertThat(access.needs(tree("Music"))).isFalse()
    }

    @Test
    fun `the output folder is needed, other folders are not`() = runBlocking<Unit> {
        outputRoot.set(tree("Music"))
        assertThat(access.needs(tree("Music"))).isTrue()
        assertThat(access.needs(tree("Podcasts"))).isFalse()
    }

    @Test
    fun `a batch whose sources are in a folder needs it, finished or not`() = runBlocking<Unit> {
        val music = tree("Music")
        val source = BatchSource(document(music, "Music/Rock/a.flac"), "a.flac", parentUri = document(music, "Music/Rock"))
        val id = batches.create(newBatch(), listOf(source))
        assertThat(access.needs(music)).isTrue()

        batches.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.COMPLETED)
        assertThat(access.needs(music)).isTrue()
    }

    @Test
    fun `a converted library song needs its folder`() = runBlocking<Unit> {
        val music = tree("Music")
        batches.create(newBatch(), listOf(BatchSource(document(music, "Music/a.flac"), "a.flac", parentUri = null)))
        assertThat(access.needs(music)).isTrue()
    }

    @Test
    fun `an earlier batch's output folder stays needed after the output folder moved`() = runBlocking<Unit> {
        val music = tree("Music")
        val id = batches.create(newBatch(), listOf(BatchSource(document(tree("Inbox"), "Inbox/a.wav"), "a.wav", null)))
        batches.updateBatch(batches.batchRows.getValue(id).copy(outputFolderUri = document(music, "Music/Convertidos/2026-10-03 14.05")))
        outputRoot.set(tree("DJ"))
        assertThat(access.needs(music)).isTrue()
    }

    @Test
    fun `a folder whose name starts like another one is not taken for it`() = runBlocking<Unit> {
        outputRoot.set(tree("Music2"))
        batches.create(newBatch(), listOf(BatchSource(document(tree("Music2"), "Music2/a.flac"), "a.flac", null)))
        assertThat(access.needs(tree("Music"))).isFalse()
    }
}
