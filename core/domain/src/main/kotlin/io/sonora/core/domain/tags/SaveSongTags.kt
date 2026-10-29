package io.sonora.core.domain.tags

import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.QueuedTrackUpdater
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagWriteOutcome
import io.sonora.core.domain.port.WriteAccessRequest

sealed interface TagSaveResult {
    /** Written (or nothing to write); [track] has the new details. */
    data class Saved(val track: Track) : TagSaveResult

    data class Invalid(val errors: Map<TagField, TagError>) : TagSaveResult

    data class NeedsWriteAccess(val request: WriteAccessRequest) : TagSaveResult

    data class Failed(val failure: TagEditFailure) : TagSaveResult
}

/**
 * Saves the edit form: checks the changed fields, writes only what changed and shows the new details in the play
 * queue (Now Playing included). An unchanged form saves nothing.
 */
class SaveSongTags(private val editor: SongTagEditor, private val queue: QueuedTrackUpdater) {

    suspend operator fun invoke(track: Track, original: SongTags, edited: EditableTags): TagSaveResult {
        val errors = edited.errors(original)
        if (errors.isNotEmpty()) return TagSaveResult.Invalid(errors)
        val changes = edited.changesFrom(original)
        if (changes.isEmpty) return TagSaveResult.Saved(track)
        return when (val outcome = editor.write(track, changes)) {
            is TagWriteOutcome.Written -> {
                queue.update(outcome.track)
                TagSaveResult.Saved(outcome.track)
            }
            is TagWriteOutcome.NeedsWriteAccess -> TagSaveResult.NeedsWriteAccess(outcome.request)
            is TagWriteOutcome.Failed -> TagSaveResult.Failed(outcome.failure)
        }
    }
}
