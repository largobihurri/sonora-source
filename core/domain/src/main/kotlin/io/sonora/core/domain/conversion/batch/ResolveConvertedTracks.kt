package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.ResolvedTrack
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import kotlin.coroutines.cancellation.CancellationException

/** A batch's converted files ready to play, and how many of those asked for could not be opened. */
data class PlayableOutputs(val tracks: List<Track>, val unavailable: Int)

/**
 * Turns a batch's converted files into [Track]s to play: each one from where it is now
 * ([ConversionBatchItem.outputUri]: the batch folder, or next to its original once it replaced it), in batch order.
 * Files that were never converted are left out; outputs that are gone or cannot be opened are skipped and counted.
 * Titles, artists, albums and covers follow the library's rules ([AlbumGrouping]) over the files' own tags, so an
 * untagged file is named after its output. Track ids are negative (`-item id`), so they never match a library track.
 */
class ResolveConvertedTracks(
    private val repository: ConversionBatchRepository,
    private val reader: ConvertedFileReader,
) {

    /** Batch [batchId]'s converted files; only item [itemId]'s when given. */
    suspend operator fun invoke(batchId: Long, itemId: Long? = null): PlayableOutputs {
        val batch = repository.getBatch(batchId) ?: return PlayableOutputs(emptyList(), 0)
        val wanted = repository.getItems(batchId)
            .filter { it.isConverted && it.outputUri != null && (itemId == null || it.id == itemId) }
            .sortedBy { it.position }
        val opened = wanted.mapNotNull { item -> open(item)?.let { item to it } }
        val scanned = opened.map { (item, file) -> scannedTrack(item, file, batch.outputFolderName) }
        val resolved = AlbumGrouping.resolve(scanned)
        val tracks = opened.zip(scanned) { (item, file), scan -> track(item, file, scan, resolved.getValue(scan.documentUri)) }
        return PlayableOutputs(tracks, unavailable = wanted.size - opened.size)
    }

    private suspend fun open(item: ConversionBatchItem): ConvertedFile? = try {
        reader.read(item.outputUri!!, item.outputName ?: item.sourceName)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // the port promises not to throw; a broken provider still only makes the file unavailable
    }

    private fun scannedTrack(item: ConversionBatchItem, file: ConvertedFile, folderName: String) = ScannedTrack(
        documentUri = item.outputUri!!,
        folderPath = folderName,
        fileName = item.outputName ?: item.sourceName,
        tags = file.tags,
        embeddedArtworkUri = file.embeddedArtworkUri,
    )

    private fun track(item: ConversionBatchItem, file: ConvertedFile, scan: ScannedTrack, resolved: ResolvedTrack) = Track(
        id = -item.id,
        uri = scan.documentUri,
        title = resolved.title,
        artist = resolved.artist,
        album = resolved.album,
        albumArtist = resolved.albumArtist,
        durationMs = file.tags.durationMs,
        trackNumber = file.tags.trackNumber,
        discNumber = file.tags.discNumber,
        year = file.tags.year,
        albumId = resolved.albumId,
        artistId = resolved.artistId,
        artworkUri = resolved.artworkUri,
        mimeType = file.mimeType,
        sizeBytes = file.sizeBytes,
        genre = file.tags.genre,
        sampleRateHz = file.tags.sampleRateHz,
        bitsPerSample = file.tags.bitsPerSample,
        channelCount = file.tags.channelCount,
    )
}
