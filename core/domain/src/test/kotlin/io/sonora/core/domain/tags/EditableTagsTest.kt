package io.sonora.core.domain.tags

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.tags.TagField.DISC_NUMBER
import io.sonora.core.domain.tags.TagField.DISC_TOTAL
import io.sonora.core.domain.tags.TagField.TRACK_NUMBER
import io.sonora.core.domain.tags.TagField.TRACK_TOTAL
import org.junit.Test

class EditableTagsTest {

    private val picture = Picture(byteArrayOf(1, 2, 3), "image/jpeg")
    private val original = SongTags(
        fields = mapOf(
            TagField.TITLE to "Song",
            TagField.YEAR to "2001-05-03",
            TRACK_NUMBER to "A1", // vinyl side numbering the form cannot parse
            TagField.COMMENT to " padded ",
        ),
        artwork = picture,
    )

    @Test
    fun `an untouched form has no changes and no errors, whatever the file holds`() {
        val form = EditableTags.of(original)

        assertThat(form.changesFrom(original).isEmpty).isTrue()
        assertThat(form.errors(original)).isEmpty()
    }

    @Test
    fun `changes are trimmed and only differing fields count`() {
        val form = EditableTags.of(original)
            .with(TagField.TITLE, "  Song  ")
            .with(TagField.ARTIST, " Artist ")
            .with(TagField.COMMENT, "padded")

        assertThat(form.changesFrom(original).fields).containsExactly(TagField.ARTIST, "Artist")
    }

    @Test
    fun `clearing a field removes it`() {
        val form = EditableTags.of(original).with(TagField.TITLE, "  ")

        assertThat(form.changesFrom(original).fields).containsExactly(TagField.TITLE, "")
        assertThat(form.errors(original)).isEmpty()
    }

    @Test
    fun `numbers must be digits within range`() {
        val form = EditableTags.of(original)
            .with(TRACK_NUMBER, "3a")
            .with(TRACK_TOTAL, "0")
            .with(DISC_NUMBER, "1000")
            .with(TagField.BPM, "128.5")

        assertThat(form.errors(original)).containsExactly(
            TRACK_NUMBER, TagError.NOT_A_NUMBER,
            TRACK_TOTAL, TagError.OUT_OF_RANGE,
            DISC_NUMBER, TagError.OUT_OF_RANGE,
            TagField.BPM, TagError.NOT_A_NUMBER,
        )
    }

    @Test
    fun `leading zeros and the upper bound are accepted`() {
        val form = EditableTags.of(original).with(TRACK_NUMBER, "003").with(TRACK_TOTAL, "999").with(TagField.BPM, "174")

        assertThat(form.errors(original)).isEmpty()
    }

    @Test
    fun `a number above its total is flagged on the number`() {
        val tracks = EditableTags.of(original).with(TRACK_NUMBER, "13").with(TRACK_TOTAL, "12")
        val discs = EditableTags.of(original).with(DISC_NUMBER, "3").with(DISC_TOTAL, "2")

        assertThat(tracks.errors(original)).containsExactly(TRACK_NUMBER, TagError.ABOVE_TOTAL)
        assertThat(discs.errors(original)).containsExactly(DISC_NUMBER, TagError.ABOVE_TOTAL)
    }

    @Test
    fun `changing only the total is checked against the stored number`() {
        val tags = SongTags(mapOf(TRACK_NUMBER to "5"))

        assertThat(EditableTags.of(tags).with(TRACK_TOTAL, "4").errors(tags)).containsExactly(TRACK_NUMBER, TagError.ABOVE_TOTAL)
    }

    @Test
    fun `years and dates starting with one are accepted`() {
        for (year in listOf("1999", "2024-02", "2024-02-29")) {
            assertThat(EditableTags.of(original).with(TagField.YEAR, year).errors(original)).isEmpty()
        }
        for (bad in listOf("99", "999", "2024-13", "2024-01-32", "20245", "May 2001")) {
            assertThat(EditableTags.of(original).with(TagField.YEAR, bad).errors(original))
                .containsExactly(TagField.YEAR, TagError.NOT_A_YEAR)
        }
    }

    @Test
    fun `free text fields take anything`() {
        val form = EditableTags.of(original).with(TagField.KEY, "8A").with(TagField.COMMENT, "line one\nline two")

        assertThat(form.errors(original)).isEmpty()
    }

    @Test
    fun `artwork changes and what the form shows`() {
        val other = Picture(byteArrayOf(9), "image/png")
        val untagged = SongTags()

        assertThat(EditableTags.of(original).shownArtwork(original)).isEqualTo(picture)
        assertThat(EditableTags.of(original).copy(artwork = ArtworkChange.Remove).shownArtwork(original)).isNull()
        assertThat(EditableTags.of(original).copy(artwork = ArtworkChange.Replace(other)).shownArtwork(original)).isEqualTo(other)
        assertThat(EditableTags.of(original).copy(artwork = ArtworkChange.Remove).changesFrom(original).artwork)
            .isEqualTo(ArtworkChange.Remove)
        // Removing a picture the file does not have is no change.
        assertThat(EditableTags.of(untagged).copy(artwork = ArtworkChange.Remove).changesFrom(untagged).isEmpty).isTrue()
    }

    @Test
    fun `pictures compare by content`() {
        assertThat(Picture(byteArrayOf(1, 2, 3), "image/jpeg")).isEqualTo(picture)
        assertThat(Picture(byteArrayOf(1, 2), "image/jpeg")).isNotEqualTo(picture)
    }
}
