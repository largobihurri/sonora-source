package io.sonora.core.domain.tags

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagReadOutcome
import io.sonora.core.domain.port.TagWriteOutcome
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SaveSongTagsTest {

    private val track = Track(
        id = 7, uri = "content://doc/7", title = "Old", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = 1, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )
    private val original = SongTags(mapOf(TagField.TITLE to "Old", TagField.TRACK_NUMBER to "1"))

    private class FakeEditor(var outcome: TagWriteOutcome) : SongTagEditor {
        val writes = mutableListOf<TagChanges>()
        override suspend fun read(track: Track) = TagReadOutcome.Read(SongTags())
        override suspend fun write(track: Track, changes: TagChanges): TagWriteOutcome {
            writes += changes
            return outcome
        }
        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED
    }

    private val updated = track.copy(title = "New")
    private val editor = FakeEditor(TagWriteOutcome.Written(updated))
    private val queued = mutableListOf<Track>()
    private val save = SaveSongTags(editor) { queued += it }

    @Test
    fun `writes only the changed fields and shows the new details in the queue`() = runBlocking<Unit> {
        val result = save(track, original, EditableTags.of(original).with(TagField.TITLE, " New "))

        assertThat(result).isEqualTo(TagSaveResult.Saved(updated))
        assertThat(editor.writes).containsExactly(TagChanges(mapOf(TagField.TITLE to "New")))
        assertThat(queued).containsExactly(updated)
    }

    @Test
    fun `an invalid form is not written`() = runBlocking<Unit> {
        val result = save(track, original, EditableTags.of(original).with(TagField.TRACK_NUMBER, "x"))

        assertThat(result).isEqualTo(TagSaveResult.Invalid(mapOf(TagField.TRACK_NUMBER to TagError.NOT_A_NUMBER)))
        assertThat(editor.writes).isEmpty()
        assertThat(queued).isEmpty()
    }

    @Test
    fun `an unchanged form saves nothing`() = runBlocking<Unit> {
        val result = save(track, original, EditableTags.of(original))

        assertThat(result).isEqualTo(TagSaveResult.Saved(track))
        assertThat(editor.writes).isEmpty()
        assertThat(queued).isEmpty()
    }

    @Test
    fun `a read-only folder asks for access and a failure is reported, the queue untouched`() = runBlocking<Unit> {
        val request = WriteAccessRequest("content://tree/a", "Music", "content://tree/a/document/a")
        editor.outcome = TagWriteOutcome.NeedsWriteAccess(request)
        val edit = EditableTags.of(original).with(TagField.TITLE, "New")

        assertThat(save(track, original, edit)).isEqualTo(TagSaveResult.NeedsWriteAccess(request))

        editor.outcome = TagWriteOutcome.Failed(TagEditFailure.NOT_VERIFIED)
        assertThat(save(track, original, edit)).isEqualTo(TagSaveResult.Failed(TagEditFailure.NOT_VERIFIED))
        assertThat(queued).isEmpty()
    }
}
