package io.sonora.core.data.library.duplicates

import io.sonora.core.data.library.duplicates.db.DuplicateDao
import io.sonora.core.data.library.duplicates.db.DuplicateDismissalEntity
import io.sonora.core.data.library.duplicates.db.DuplicateFileRow
import io.sonora.core.data.library.duplicates.db.DuplicateFingerprintEntity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.AudioFormats
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.library.duplicates.DuplicateAlerts
import io.sonora.core.domain.library.duplicates.DuplicateCopy
import io.sonora.core.domain.library.duplicates.DuplicateDismissals
import io.sonora.core.domain.library.duplicates.DuplicateFingerprint
import io.sonora.core.domain.library.duplicates.DuplicateFingerprints
import io.sonora.core.domain.library.duplicates.DuplicateFirstRun
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateLibrary
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The library index's files, as the duplicate finder compares them. */
class RoomDuplicateLibrary @Inject constructor(private val dao: DuplicateDao) : DuplicateLibrary {
    override suspend fun copies(): List<DuplicateCopy> = dao.files().map { it.toCopy() }

    private fun DuplicateFileRow.toCopy() = DuplicateCopy(
        uri = documentUri,
        title = title,
        artist = artist,
        tagTitle = tagTitle,
        tagArtist = tagArtist,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        format = AudioFormats.of(codec, mimeType),
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        channelCount = channelCount,
        folderPath = folderPath,
        fileName = fileName,
        hasArtwork = embeddedArtworkUri != null,
        tagsFilled = DuplicateCopy.tagsFilled(
            TrackTags(
                title = tagTitle, artist = tagArtist, album = tagAlbum, albumArtist = tagAlbumArtist, genre = genre,
                year = year, trackNumber = trackNumber,
            ),
        ),
        dateAddedSec = dateAddedSec,
    )
}

/** "Not a duplicate" groups, each stored as one row per file under a stable id of the whole group. */
class RoomDuplicateDismissals @Inject constructor(private val dao: DuplicateDao) : DuplicateDismissals {
    override suspend fun dismissed(): List<Set<String>> =
        dao.dismissals().groupBy({ it.dismissalId }, { it.documentUri }).values.map { it.toSet() }

    override suspend fun dismiss(uris: Set<String>) {
        if (uris.isEmpty()) return
        val id = AlbumGrouping.stableId("duplicates", DuplicateGroup.keyOf(uris))
        dao.insertDismissals(uris.map { DuplicateDismissalEntity(id, it) })
    }
}

class RoomDuplicateFingerprints @Inject constructor(private val dao: DuplicateDao) : DuplicateFingerprints {
    override suspend fun all(): List<DuplicateFingerprint> = dao.fingerprints().map { it.toDomain() }

    override suspend fun update(fingerprints: List<DuplicateFingerprint>, gone: Collection<String>) {
        if (fingerprints.isEmpty() && gone.isEmpty()) return
        dao.updateFingerprints(fingerprints.map(DuplicateFingerprintEntity::of), gone)
    }
}

/** The duplicate checks' settings row: missing until first written, read as its defaults. */
class RoomDuplicateSettings @Inject constructor(private val dao: DuplicateDao) : DuplicateAlerts, DuplicateFirstRun {
    override val enabled: Flow<Boolean> = dao.settings().map { it?.notify ?: true }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) = dao.editSettings { it.copy(notify = enabled) }

    override suspend fun lastNotifiedCount(): Int = dao.getSettings()?.lastNotifiedCount ?: 0

    override suspend fun setLastNotifiedCount(count: Int) = dao.editSettings { it.copy(lastNotifiedCount = count) }

    override suspend fun done(): Boolean = dao.getSettings()?.firstAnalysisDone ?: false

    override suspend fun markDone() = dao.editSettings { it.copy(firstAnalysisDone = true) }
}
