package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ResolveConvertedTracksTest {

    private val repository = FakeRepository()

    /** Files by URI; a URI not listed is gone. A URI in [broken] makes the reader throw. */
    private val files = mutableMapOf<String, ConvertedFile>()
    private val broken = mutableSetOf<String>()
    private val reads = mutableListOf<String>()
    private val reader = ConvertedFileReader { uri, _ ->
        reads += uri
        if (uri in broken) throw IOException("provider died")
        files[uri]
    }
    private val resolve = ResolveConvertedTracks(repository, reader)

    private fun file(title: String? = null, artist: String? = null, album: String? = null, artwork: String? = null) =
        ConvertedFile(TrackTags(title = title, artist = artist, album = album, durationMs = 180_000), artwork, "audio/aiff", 42)

    /** A batch of [names]; each item then gets [states]'s state with an output named after its source. */
    private fun batch(vararg states: Pair<String, BatchItemState>): Long = runBlocking {
        val id = repository.create(newBatch(), states.map { (name, _) -> BatchSource("content://src/$name", name, "content://src") })
        states.forEachIndexed { position, (name, state) ->
            val item = repository.item(id, position)
            val output = name.substringBeforeLast('.') + ".aiff"
            val updated = when (state) {
                BatchItemState.DONE -> item.copy(state = state, outputUri = "content://batch/$output", outputName = output, result = FakeConverter.result())
                BatchItemState.REPLACED_ORIGINAL -> item.copy(state = state, outputUri = "content://src/$output", outputName = output)
                // A failed replacement keeps its verified output in the batch folder.
                BatchItemState.FAILED -> if (name.startsWith("kept")) {
                    item.copy(state = state, outputUri = "content://batch/$output", outputName = output, result = FakeConverter.result())
                } else {
                    item.copy(state = state, failureReason = "damaged")
                }
                else -> item.copy(state = state)
            }
            repository.updateItem(updated)
        }
        id
    }

    private fun itemOf(batchId: Long, position: Int): ConversionBatchItem = repository.item(batchId, position)

    @Test
    fun `every converted file plays from where it is now, in batch order`() = runBlocking<Unit> {
        val id = batch(
            "a.flac" to BatchItemState.DONE,
            "b.mp3" to BatchItemState.FAILED,
            "c.wav" to BatchItemState.REPLACED_ORIGINAL,
            "d.mp3" to BatchItemState.SKIPPED,
            "kept.flac" to BatchItemState.FAILED,
        )
        listOf("content://batch/a.aiff", "content://src/c.aiff", "content://batch/kept.aiff").forEach { files[it] = file() }

        val result = resolve(id)

        assertThat(result.tracks.map { it.uri })
            .containsExactly("content://batch/a.aiff", "content://src/c.aiff", "content://batch/kept.aiff").inOrder()
        assertThat(result.unavailable).isEqualTo(0)
        // Files that were never converted are not even looked for.
        assertThat(reads).containsExactly("content://batch/a.aiff", "content://src/c.aiff", "content://batch/kept.aiff")
    }

    @Test
    fun `outputs that are gone or cannot be opened are skipped and counted, the rest keep their order`() = runBlocking<Unit> {
        val id = batch(
            "a.flac" to BatchItemState.DONE,
            "b.flac" to BatchItemState.DONE,
            "c.flac" to BatchItemState.DONE,
            "d.flac" to BatchItemState.DONE,
        )
        files["content://batch/a.aiff"] = file(title = "A")
        files["content://batch/c.aiff"] = file(title = "C")
        broken += "content://batch/d.aiff"

        val result = resolve(id)

        assertThat(result.tracks.map { it.title }).containsExactly("A", "C").inOrder()
        assertThat(result.unavailable).isEqualTo(2)
    }

    @Test
    fun `a track carries the converted file's tags, artwork and details`() = runBlocking<Unit> {
        val id = batch("Better will come.flac" to BatchItemState.DONE)
        files["content://batch/Better will come.aiff"] =
            file(title = "Better Will Come", artist = "Atili", album = "Freedom Fighter", artwork = "file:///cache/artwork/x.jpg")

        val track = resolve(id).tracks.single()

        assertThat(track.title).isEqualTo("Better Will Come")
        assertThat(track.artist).isEqualTo("Atili")
        assertThat(track.album).isEqualTo("Freedom Fighter")
        assertThat(track.artworkUri).isEqualTo("file:///cache/artwork/x.jpg")
        assertThat(track.durationMs).isEqualTo(180_000)
        assertThat(track.mimeType).isEqualTo("audio/aiff")
        assertThat(track.sizeBytes).isEqualTo(42)
    }

    @Test
    fun `an untagged file is named after its output file`() = runBlocking<Unit> {
        val id = batch("Intro.mp3" to BatchItemState.DONE)
        files["content://batch/Intro.aiff"] = file()

        val track = resolve(id).tracks.single()

        assertThat(track.title).isEqualTo("Intro")
        assertThat(track.artist).isEqualTo(AlbumGrouping.UNKNOWN_ARTIST)
        assertThat(track.artworkUri).isNull()
    }

    @Test
    fun `tracks get ids of their own that no library track uses`() = runBlocking<Unit> {
        val id = batch("a.flac" to BatchItemState.DONE, "b.flac" to BatchItemState.DONE)
        files["content://batch/a.aiff"] = file()
        files["content://batch/b.aiff"] = file()

        val ids = resolve(id).tracks.map { it.id }

        assertThat(ids.toSet()).hasSize(2)
        assertThat(ids.all { it < 0 }).isTrue()
    }

    @Test
    fun `one item resolves just that file`() = runBlocking<Unit> {
        val id = batch("a.flac" to BatchItemState.DONE, "b.flac" to BatchItemState.REPLACED_ORIGINAL)
        files["content://batch/a.aiff"] = file(title = "A")
        files["content://src/b.aiff"] = file(title = "B")

        val result = resolve(id, itemOf(id, 1).id)

        assertThat(result.tracks.map { it.title }).containsExactly("B")
        assertThat(result.unavailable).isEqualTo(0)
        assertThat(reads).containsExactly("content://src/b.aiff")
    }

    @Test
    fun `one item that is gone gives nothing to play and counts as unavailable`() = runBlocking<Unit> {
        val id = batch("a.flac" to BatchItemState.DONE)

        val result = resolve(id, itemOf(id, 0).id)

        assertThat(result.tracks).isEmpty()
        assertThat(result.unavailable).isEqualTo(1)
    }

    @Test
    fun `an item that was not converted is never played`() = runBlocking<Unit> {
        val id = batch("a.flac" to BatchItemState.FAILED, "b.flac" to BatchItemState.SKIPPED)

        assertThat(resolve(id, itemOf(id, 0).id)).isEqualTo(PlayableOutputs(emptyList(), 0))
        assertThat(resolve(id)).isEqualTo(PlayableOutputs(emptyList(), 0))
        assertThat(reads).isEmpty()
    }

    @Test
    fun `an unknown batch has nothing to play`() = runBlocking<Unit> {
        assertThat(resolve(99)).isEqualTo(PlayableOutputs(emptyList(), 0))
    }
}
