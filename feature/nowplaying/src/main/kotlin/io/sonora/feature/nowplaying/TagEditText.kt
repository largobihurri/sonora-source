package io.sonora.feature.nowplaying

import androidx.annotation.StringRes
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.tags.EditableTags
import io.sonora.core.domain.tags.TagError
import io.sonora.core.domain.tags.TagField

/** Labels and messages of the tag edit sheet from string resources, kept pure so they are unit-tested. */
object TagEditText {

    @StringRes
    fun label(field: TagField): Int = when (field) {
        TagField.TITLE -> R.string.file_info_title
        TagField.ARTIST -> R.string.file_info_artist
        TagField.ALBUM -> R.string.file_info_album
        TagField.ALBUM_ARTIST -> R.string.file_info_album_artist
        TagField.GENRE -> R.string.file_info_genre
        TagField.YEAR -> R.string.file_info_year
        TagField.TRACK_NUMBER, TagField.TRACK_TOTAL -> R.string.file_info_track
        TagField.DISC_NUMBER, TagField.DISC_TOTAL -> R.string.file_info_disc
        TagField.BPM -> R.string.tag_edit_bpm
        TagField.KEY -> R.string.tag_edit_key
        TagField.COMMENT -> R.string.file_info_comment
    }

    /** "Track: use digits only." for each error in [fields], in form order. */
    fun errors(errors: Map<TagField, TagError>, fields: List<TagField>, strings: Strings): List<String> =
        fields.mapNotNull { field ->
            errors[field]?.let { strings.get(R.string.tag_edit_field_error, strings.get(label(field)), error(it, strings)) }
        }.distinct()

    fun error(error: TagError, strings: Strings): String = when (error) {
        TagError.NOT_A_NUMBER -> strings.get(R.string.tag_edit_error_not_a_number)
        TagError.OUT_OF_RANGE -> strings.get(R.string.tag_edit_error_out_of_range, EditableTags.MAX_NUMBER)
        TagError.ABOVE_TOTAL -> strings.get(R.string.tag_edit_error_above_total)
        TagError.NOT_A_YEAR -> strings.get(R.string.tag_edit_error_not_a_year)
    }

    fun problem(problem: EditProblem, strings: Strings): String = when (problem) {
        is EditProblem.NeedsAccess -> strings.get(R.string.tag_edit_needs_access, problem.request.folderName)
        is EditProblem.WrongFolder -> strings.get(R.string.tag_edit_wrong_folder, problem.request.folderName)
        EditProblem.AccessRefused -> strings.get(R.string.tag_edit_access_refused)
        is EditProblem.SaveFailed -> failure(problem.failure, strings)
        EditProblem.PictureUnusable -> strings.get(R.string.tag_edit_picture_unusable)
    }

    fun failure(failure: TagEditFailure, strings: Strings): String = strings.get(
        when (failure) {
            TagEditFailure.UNSUPPORTED_FORMAT -> R.string.tag_edit_unsupported
            TagEditFailure.READ_ONLY_LOCATION -> R.string.tag_edit_read_only
            TagEditFailure.UNREADABLE -> R.string.tag_edit_unreadable
            TagEditFailure.NOT_ENOUGH_SPACE -> R.string.tag_edit_no_space
            TagEditFailure.NOT_VERIFIED -> R.string.tag_edit_not_verified
            TagEditFailure.NOT_SAVED -> R.string.tag_edit_not_saved
            TagEditFailure.INTERRUPTED -> R.string.tag_edit_interrupted
        },
    )
}
