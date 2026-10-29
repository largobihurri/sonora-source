package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagReadOutcome
import io.sonora.core.domain.port.TagWriteOutcome
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import io.sonora.core.domain.tags.ArtworkChange
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagChanges
import io.sonora.core.domain.tags.TagError
import io.sonora.core.domain.tags.TagField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class TagEditorViewModelTest {

    private val track = Track(
        id = 1, uri = "content://tree/music/document/a.flac", title = "Old", artist = "A", album = "B",
        albumArtist = null, durationMs = 1_000, trackNumber = 1, discNumber = null, year = null, albumId = 1,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )
    private val cover = Picture(byteArrayOf(1, 2), "image/jpeg")
    private val stored = SongTags(mapOf(TagField.TITLE to "Old", TagField.TRACK_NUMBER to "1"), cover)
    private val request = WriteAccessRequest("content://tree/music", "Music", "content://tree/music/document/music")

    private class FakeEditor : SongTagEditor {
        var readOutcome: TagReadOutcome = TagReadOutcome.Read(SongTags())
        val writeOutcomes = ArrayDeque<TagWriteOutcome>()
        val writes = mutableListOf<TagChanges>()
        var gate: CompletableDeferred<Unit>? = null
        var grant = WriteGrant.GRANTED
        var grantGate: CompletableDeferred<Unit>? = null
        val grants = mutableListOf<String>()

        override suspend fun read(track: Track) = readOutcome
        override suspend fun write(track: Track, changes: TagChanges): TagWriteOutcome {
            writes += changes
            gate?.await()
            return writeOutcomes.removeFirst()
        }
        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant {
            grants += pickedTreeUri
            grantGate?.await()
            return grant
        }
    }

    private val editor = FakeEditor()
    private val loaded = mutableMapOf<String, Picture?>()
    private val queued = mutableListOf<Track>()
    private lateinit var viewModel: TagEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        editor.readOutcome = TagReadOutcome.Read(stored)
        viewModel = TagEditorViewModel(editor, { loaded[it] }, { queued += it })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val editing get() = viewModel.state.value as TagEditorState.Editing

    @Test
    fun `opening reads the file's tags into the form`() {
        viewModel.open(track)

        assertThat(editing.form[TagField.TITLE]).isEqualTo("Old")
        assertThat(editing.form.shownArtwork(editing.original)).isEqualTo(cover)
        assertThat(editing.dirty).isFalse()
        assertThat(editing.canSave).isFalse()
    }

    @Test
    fun `a file that cannot be edited says why`() {
        editor.readOutcome = TagReadOutcome.Failed(TagEditFailure.UNSUPPORTED_FORMAT)

        viewModel.open(track)

        assertThat(viewModel.state.value).isEqualTo(TagEditorState.Unavailable(TagEditFailure.UNSUPPORTED_FORMAT))
    }

    @Test
    fun `typing updates the form and shows errors as they appear`() {
        viewModel.open(track)

        viewModel.change(TagField.TRACK_NUMBER, "x")
        assertThat(editing.errors).containsExactly(TagField.TRACK_NUMBER, TagError.NOT_A_NUMBER)
        assertThat(editing.canSave).isFalse()

        viewModel.change(TagField.TRACK_NUMBER, "2")
        assertThat(editing.errors).isEmpty()
        assertThat(editing.canSave).isTrue()
    }

    @Test
    fun `saving writes the changes, updates the queue and reports the new track`() {
        val updated = track.copy(title = "New")
        editor.writeOutcomes += TagWriteOutcome.Written(updated)
        editor.gate = CompletableDeferred()
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")

        viewModel.save()
        assertThat(editing.saving).isTrue()
        assertThat(editing.canSave).isFalse()
        editor.gate!!.complete(Unit)

        assertThat(viewModel.state.value).isEqualTo(TagEditorState.Saved(updated))
        assertThat(editor.writes).containsExactly(TagChanges(mapOf(TagField.TITLE to "New")))
        assertThat(queued).containsExactly(updated)
    }

    @Test
    fun `a read-only folder asks for access and the right folder retries the save`() {
        val updated = track.copy(title = "New")
        editor.writeOutcomes += TagWriteOutcome.NeedsWriteAccess(request)
        editor.writeOutcomes += TagWriteOutcome.Written(updated)
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")

        viewModel.save()
        assertThat(editing.problem).isEqualTo(EditProblem.NeedsAccess(request))
        assertThat(editing.saving).isFalse()

        viewModel.accessPicked(request.treeUri)

        assertThat(editor.grants).containsExactly(request.treeUri)
        assertThat(viewModel.state.value).isEqualTo(TagEditorState.Saved(updated))
    }

    @Test
    fun `edits made while the grant is under way are the ones saved`() {
        val updated = track.copy(title = "New", artist = "Z")
        editor.writeOutcomes += TagWriteOutcome.NeedsWriteAccess(request)
        editor.writeOutcomes += TagWriteOutcome.Written(updated)
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")
        viewModel.save()
        editor.grantGate = CompletableDeferred()

        viewModel.accessPicked(request.treeUri)
        viewModel.change(TagField.ARTIST, "Z")
        editor.grantGate!!.complete(Unit)

        assertThat(editor.writes.last()).isEqualTo(TagChanges(mapOf(TagField.TITLE to "New", TagField.ARTIST to "Z")))
        assertThat(viewModel.state.value).isEqualTo(TagEditorState.Saved(updated))
    }

    @Test
    fun `a save that finishes after closing never reaches the next song's editor`() {
        val other = track.copy(id = 2, uri = "content://tree/music/document/b.flac", title = "Other")
        editor.writeOutcomes += TagWriteOutcome.Failed(TagEditFailure.NOT_SAVED)
        editor.gate = CompletableDeferred()
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")
        viewModel.save()

        viewModel.close()
        viewModel.open(other)
        editor.gate!!.complete(Unit)

        assertThat(editing.track).isEqualTo(other)
        assertThat(editing.problem).isNull()
        assertThat(editor.writes).hasSize(1) // the save still ran to its end
    }

    @Test
    fun `a grant that finishes after closing neither saves nor reaches the next song's editor`() {
        val other = track.copy(id = 2, uri = "content://tree/music/document/b.flac", title = "Other")
        editor.writeOutcomes += TagWriteOutcome.NeedsWriteAccess(request)
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")
        viewModel.save()
        editor.grantGate = CompletableDeferred()
        viewModel.accessPicked(request.treeUri)

        viewModel.close()
        viewModel.open(other)
        editor.grantGate!!.complete(Unit)

        assertThat(editing.track).isEqualTo(other)
        assertThat(editing.problem).isNull()
        assertThat(editor.writes).hasSize(1)
    }

    @Test
    fun `another folder or a refused grant keeps the form with the reason`() {
        editor.writeOutcomes += TagWriteOutcome.NeedsWriteAccess(request)
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")
        viewModel.save()

        editor.grant = WriteGrant.WRONG_FOLDER
        viewModel.accessPicked("content://tree/other")
        assertThat(editing.problem).isEqualTo(EditProblem.WrongFolder(request))

        editor.grant = WriteGrant.REFUSED
        viewModel.accessPicked(request.treeUri)
        assertThat(editing.problem).isEqualTo(EditProblem.AccessRefused)
        assertThat(editing.form[TagField.TITLE]).isEqualTo("New")
        assertThat(editor.writes).hasSize(1)
    }

    @Test
    fun `a failed save keeps the edits and says why`() {
        editor.writeOutcomes += TagWriteOutcome.Failed(TagEditFailure.NOT_VERIFIED)
        viewModel.open(track)
        viewModel.change(TagField.TITLE, "New")

        viewModel.save()

        assertThat(editing.problem).isEqualTo(EditProblem.SaveFailed(TagEditFailure.NOT_VERIFIED))
        assertThat(editing.form[TagField.TITLE]).isEqualTo("New")
        viewModel.dismissProblem()
        assertThat(editing.problem).isNull()
    }

    @Test
    fun `pictures can be replaced or removed, and an unusable one is reported`() {
        val picked = Picture(byteArrayOf(9), "image/png")
        loaded["content://picked/1"] = picked
        viewModel.open(track)

        viewModel.pickPicture("content://picked/1")
        assertThat(editing.form.artwork).isEqualTo(ArtworkChange.Replace(picked))
        assertThat(editing.canSave).isTrue()

        viewModel.pickPicture("content://picked/broken")
        assertThat(editing.problem).isEqualTo(EditProblem.PictureUnusable)
        assertThat(editing.form.artwork).isEqualTo(ArtworkChange.Replace(picked))

        viewModel.removePicture()
        assertThat(editing.form.shownArtwork(editing.original)).isNull()
    }

    @Test
    fun `closing forgets the form`() {
        viewModel.open(track)

        viewModel.close()

        assertThat(viewModel.state.value).isEqualTo(TagEditorState.Closed)
    }
}
