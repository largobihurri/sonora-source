package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.export.ExportText
import io.sonora.core.domain.export.SourceTags
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** The device library's tags from a source's metadata (as the converter reads it, front cover included). */
internal object ExportTags {

    /** [metadata] as [SourceTags]; the album artist stands in for a missing artist. No metadata gives no tags. */
    fun of(metadata: TrackMetadata?): SourceTags {
        if (metadata == null) return SourceTags.NONE
        val text = metadata.text
        return SourceTags(
            title = text[MetadataTextField.TITLE],
            artist = text[MetadataTextField.ARTIST] ?: text[MetadataTextField.ALBUM_ARTIST],
            album = text[MetadataTextField.ALBUM],
            genre = text[MetadataTextField.GENRE],
            trackNumber = metadata.trackNumber,
            year = ExportText.yearOf(metadata.date),
            comment = metadata.comment,
            cover = metadata.cover,
        )
    }

    /**
     * [metadata] as [SourceTags]; without any, [checkReadable] tells a file that has no readable tags ([SourceTags.NONE])
     * from a document that cannot be opened now (it throws), which is reported as an [IOException] so it is never
     * taken for a file without tags.
     */
    suspend fun orUnreadable(metadata: TrackMetadata?, checkReadable: suspend () -> Unit): SourceTags {
        if (metadata != null) return of(metadata)
        try {
            checkReadable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // A revoked grant (SecurityException) or a provider gone (IllegalStateException, ...).
            throw IOException("The document cannot be read", e)
        }
        return SourceTags.NONE
    }
}
