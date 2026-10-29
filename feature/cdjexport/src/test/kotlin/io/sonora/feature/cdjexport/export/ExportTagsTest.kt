package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.export.SourceTags
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExportTagsTest {

    private val cover = CoverArt(byteArrayOf(1, 2), "image/jpeg", 500, 500)

    @Test
    fun `a source's metadata gives the device library's tags, comment and cover`() {
        val metadata = TrackMetadata(
            text = mapOf(
                MetadataTextField.TITLE to "Dub Warrior",
                MetadataTextField.ARTIST to "Aryeh Yah",
                MetadataTextField.ALBUM to "Jah Warrior",
                MetadataTextField.GENRE to "Dub; Reggae",
                MetadataTextField.COMPOSER to "unused",
            ),
            date = "2019-04-01",
            trackNumber = 2,
            comment = "Visit\nthe label",
            cover = cover,
        )

        assertThat(ExportTags.of(metadata)).isEqualTo(
            SourceTags(
                title = "Dub Warrior", artist = "Aryeh Yah", album = "Jah Warrior", genre = "Dub; Reggae",
                trackNumber = 2, year = 2019, comment = "Visit\nthe label", cover = cover,
            ),
        )
    }

    @Test
    fun `the album artist stands in for a missing artist, and no metadata gives no tags`() {
        val metadata = TrackMetadata(text = mapOf(MetadataTextField.ALBUM_ARTIST to "Various"))

        assertThat(ExportTags.of(metadata).artist).isEqualTo("Various")
        assertThat(ExportTags.of(null)).isEqualTo(SourceTags.NONE)
    }

    @Test
    fun `no metadata from a file that opens means no tags, from one that does not means try again later`() = runTest {
        var checks = 0
        assertThat(ExportTags.orUnreadable(null) { checks++ }).isEqualTo(SourceTags.NONE)
        assertThat(checks).isEqualTo(1)

        val tagged = TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Dub Warrior"))
        assertThat(ExportTags.orUnreadable(tagged) { error("not checked when there are tags") }.title).isEqualTo("Dub Warrior")

        val missing = runCatching { ExportTags.orUnreadable(null) { throw FileNotFoundException("gone") } }.exceptionOrNull()
        assertThat(missing).isInstanceOf(FileNotFoundException::class.java)
        val revoked = runCatching { ExportTags.orUnreadable(null) { throw SecurityException("no grant") } }.exceptionOrNull()
        assertThat(revoked).isInstanceOf(IOException::class.java)
        assertThat(revoked!!.cause).isInstanceOf(SecurityException::class.java)
    }
}
