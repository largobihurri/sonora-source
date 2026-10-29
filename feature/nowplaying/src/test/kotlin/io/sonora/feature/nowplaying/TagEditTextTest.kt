package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.tags.TagError
import io.sonora.core.domain.tags.TagField
import org.junit.Test

class TagEditTextTest {

    private val english = XmlStrings.of()
    private val request = WriteAccessRequest("content://tree/music", "Music/Rock", "content://tree/music/document/music")

    @Test
    fun `field errors name the field, in form order, once per row`() {
        val errors = mapOf(TagField.YEAR to TagError.NOT_A_YEAR, TagField.TRACK_NUMBER to TagError.ABOVE_TOTAL, TagField.TRACK_TOTAL to TagError.OUT_OF_RANGE)

        val lines = TagEditText.errors(errors, TagField.entries, english)

        assertThat(lines).containsExactly(
            "Year: use a year such as 2024, or a date such as 2024-05-31.",
            "Track: can't be higher than the total.",
            "Track: use a number from 1 to 999.",
        ).inOrder()
    }

    @Test
    fun `problems explain what happened and name the folder to pick`() {
        assertThat(TagEditText.problem(EditProblem.NeedsAccess(request), english))
            .isEqualTo("Sonora can only read the folder “Music/Rock”. To save tags, choose that folder again and allow access.")
        assertThat(TagEditText.problem(EditProblem.SaveFailed(TagEditFailure.NOT_VERIFIED), english))
            .isEqualTo("The changes couldn't be checked after writing them, so the file was left unchanged.")
    }

    @Test
    fun `every message resolves in every language`() {
        for (strings in listOf(english, XmlStrings.of("-es"), XmlStrings.of("-eu"))) {
            TagField.entries.forEach { strings.get(TagEditText.label(it)) }
            TagError.entries.forEach { assertThat(TagEditText.error(it, strings)).isNotEmpty() }
            TagEditFailure.entries.forEach { assertThat(TagEditText.failure(it, strings)).isNotEmpty() }
            listOf(EditProblem.NeedsAccess(request), EditProblem.WrongFolder(request), EditProblem.AccessRefused, EditProblem.PictureUnusable)
                .forEach { assertThat(TagEditText.problem(it, strings)).isNotEmpty() }
        }
        assertThat(TagEditText.problem(EditProblem.WrongFolder(request), XmlStrings.of("-eu")))
            .isEqualTo("Beste karpeta bat da hori. Aldaketak baimentzeko, aukeratu «Music/Rock» karpeta.")
    }
}
