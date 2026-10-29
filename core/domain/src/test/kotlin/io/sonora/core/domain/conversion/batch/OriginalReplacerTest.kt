package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.ConversionBatchItem
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OriginalReplacerTest {

    private val files = FakeFiles()
    private val music = files.folder("Music")
    private val batchFolder = files.folder("2026-10-03 14.05")
    private val access = FakeAccess(writable = setOf(music))
    private val converter = FakeConverter(files)
    private val repository = FakeRepository()
    private val replacer = OriginalReplacer(files, access, converter, repository)
    private val xdj = ConversionProfiles.XDJ_700

    /** A converted item: [sourceName] in Music, its verified output "AIFF:<content>" in the batch folder. */
    private fun converted(sourceName: String, outputName: String = "Song.aiff", parent: String? = music): ConversionBatchItem {
        val source = files.file(music, sourceName, "pcm")
        val output = files.file(batchFolder, outputName, "AIFF:pcm")
        return ConversionBatchItem(
            id = 1,
            batchId = 1,
            position = 0,
            sourceUri = source,
            sourceName = sourceName,
            sourceParentUri = parent,
            state = BatchItemState.DONE,
            outputUri = output,
            outputName = outputName,
            tagsWritten = true,
            result = FakeConverter.result(),
            profileId = ConversionProfiles.XDJ_700.id,
        )
    }

    @Test
    fun `the verified output moves next to the original, which is deleted`() = runBlocking<Unit> {
        val item = converted("Song.flac")

        val replaced = replacer.replace(xdj, item)

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song.aiff")
        assertThat(replaced.outputUri).isEqualTo(files.uriOf(music, "Song.aiff"))
        assertThat(replaced.outputName).isEqualTo("Song.aiff")
        assertThat(replaced.suffixedName).isFalse()
        assertThat(replaced.failureReason).isNull()
        assertThat(files.content(replaced.outputUri!!)).isEqualTo("AIFF:pcm")
        // Moved, not copied: the batch folder no longer holds it.
        assertThat(files.names(batchFolder)).isEmpty()
        // The copy next to the original was verified before the original was deleted.
        assertThat(converter.verified).containsExactly(replaced.outputUri)
    }

    @Test
    fun `an AIFF original is replaced by a file with its own name`() = runBlocking<Unit> {
        val item = converted("Song.aiff")

        val replaced = replacer.replace(xdj, item)

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song.aiff")
        assertThat(files.content(replaced.outputUri!!)).isEqualTo("AIFF:pcm")
        assertThat(replaced.outputName).isEqualTo("Song.aiff")
        assertThat(replaced.suffixedName).isFalse()
    }

    @Test
    fun `an AIFF original matches its name ignoring case`() = runBlocking<Unit> {
        val replaced = replacer.replace(xdj, converted("Song.AIFF"))

        assertThat(files.names(music)).containsExactly("Song.aiff")
        assertThat(replaced.suffixedName).isFalse()
    }

    @Test
    fun `without rename support an AIFF original's replacement keeps the suffixed name`() = runBlocking<Unit> {
        files.renameSupported = false

        val replaced = replacer.replace(xdj, converted("Song.aiff"))

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song (2).aiff")
        assertThat(replaced.outputName).isEqualTo("Song (2).aiff")
        assertThat(replaced.suffixedName).isTrue()
    }

    @Test
    fun `another file holding the output's name is left alone`() = runBlocking<Unit> {
        val other = files.file(music, "Song.aiff", "someone else's")

        val replaced = replacer.replace(xdj, converted("Song.flac"))

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song.aiff", "Song (2).aiff")
        assertThat(files.content(other)).isEqualTo("someone else's")
        assertThat(replaced.outputName).isEqualTo("Song (2).aiff")
        assertThat(replaced.suffixedName).isTrue()
    }

    @Test
    fun `without write access to the original's folder nothing is touched`() = runBlocking<Unit> {
        access.writable = emptySet()
        val item = converted("Song.flac")

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("write access")
        assertThat(files.names(music)).containsExactly("Song.flac")
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
        assertThat(result.canReplaceOriginal).isTrue()
    }

    @Test
    fun `an individually picked file has no known folder and is not replaced`() = runBlocking<Unit> {
        val result = replacer.replace(xdj, converted("Song.flac", parent = null))

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("folder")
        assertThat(files.names(music)).containsExactly("Song.flac")
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
    }

    @Test
    fun `a copy that does not verify is deleted and the original kept`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        // The copy next to the original gets corrupted on the way.
        converter.badFolders += music

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("does not decode")
        assertThat(files.names(music)).containsExactly("Song.flac")
        assertThat(files.content(item.sourceUri)).isEqualTo("pcm")
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
    }

    @Test
    fun `a copy that cannot be written is cleaned up and the original kept`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        files.failCopyInto += music

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("Disk full")
        assertThat(files.names(music)).containsExactly("Song.flac")
    }

    @Test
    fun `an original that cannot be deleted stays, and its copy is removed again`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        files.undeletable += item.sourceUri

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("could not be deleted")
        assertThat(files.names(music)).containsExactly("Song.flac")
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
    }

    @Test
    fun `a missing batch output fails without touching the original`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        files.delete(item.outputUri!!)

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(files.names(music)).containsExactly("Song.flac")
    }

    @Test
    fun `a write-access check that throws fails without touching anything`() = runBlocking<Unit> {
        access.failure = SecurityException("provider died")
        val item = converted("Song.flac")

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.FAILED)
        assertThat(result.failureReason).contains("provider died")
        assertThat(result.outputUri).isEqualTo(item.outputUri)
        assertThat(result.canReplaceOriginal).isTrue()
        assertThat(files.names(music)).containsExactly("Song.flac")
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
    }

    @Test
    fun `the replacement is recorded even when cancelled after the original is deleted`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        lateinit var job: Job
        files.onDelete = { uri -> if (uri == item.sourceUri) job.cancel() }

        job = launch(start = CoroutineStart.LAZY) { replacer.replace(xdj, item) }
        job.join()

        val recorded = repository.itemRows.getValue(item.id)
        assertThat(recorded.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(recorded.outputUri).isEqualTo(files.uriOf(music, "Song.aiff"))
        assertThat(files.content(recorded.outputUri!!)).isEqualTo("AIFF:pcm")
        assertThat(files.names(batchFolder)).isEmpty()
    }

    @Test
    fun `the batch copy is kept while the replacement cannot be recorded`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        repository.failItemWrite = { IOException("disk full") }

        val result = replacer.replace(xdj, item)

        assertThat(result.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        // The history still points at the batch copy, which therefore must stay.
        assertThat(files.names(batchFolder)).containsExactly("Song.aiff")
        assertThat(files.names(music)).containsExactly("Song.aiff")
    }

    @Test
    fun `a rename that throws after the original is gone keeps the suffixed copy on record`() = runBlocking<Unit> {
        files.renameFailure = IOException("provider died")

        val replaced = replacer.replace(xdj, converted("Song.aiff"))

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(replaced.outputName).isEqualTo("Song (2).aiff")
        assertThat(replaced.outputUri).isEqualTo(files.uriOf(music, "Song (2).aiff"))
        assertThat(replaced.suffixedName).isTrue()
        assertThat(repository.itemRows.getValue(replaced.id)).isEqualTo(replaced)
    }

    @Test
    fun `an original already gone, after an interrupted replacement, is completed rather than failed`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        files.delete(item.sourceUri)

        val replaced = replacer.replace(xdj, item)

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song.aiff")
        assertThat(files.names(batchFolder)).isEmpty()
    }

    @Test
    fun `an original the storage deletes while reporting a failure counts as deleted`() = runBlocking<Unit> {
        val item = converted("Song.flac")
        files.deleteReportsFailure += item.sourceUri

        val replaced = replacer.replace(xdj, item)

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(music)).containsExactly("Song.aiff")
    }

    @Test
    fun `a name lengthened by the new extension still fits FAT`() = runBlocking<Unit> {
        val stem = "x".repeat(251)
        val replaced = replacer.replace(xdj, converted("$stem.m4a"))

        assertThat(replaced.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(replaced.outputName!!.length).isAtMost(BatchNaming.MAX_NAME_BYTES)
        assertThat(replaced.outputName).endsWith(".aiff")
    }
}
