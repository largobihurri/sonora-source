package io.sonora.feature.settings.duplicates

import io.sonora.core.domain.library.duplicates.ContentHasher
import io.sonora.core.domain.library.duplicates.DuplicateAlerts
import io.sonora.core.domain.library.duplicates.DuplicateCopy
import io.sonora.core.domain.library.duplicates.DuplicateDismissals
import io.sonora.core.domain.library.duplicates.DuplicateFingerprint
import io.sonora.core.domain.library.duplicates.DuplicateFingerprints
import io.sonora.core.domain.library.duplicates.DuplicateLibrary
import kotlinx.coroutines.flow.MutableStateFlow

/** A copy of "Song" by "Band": a 16-bit/44.1 kHz FLAC unless told otherwise. */
internal fun copy(
    name: String,
    format: String? = "FLAC",
    bitrateKbps: Int? = 900,
    sampleRateHz: Int? = 44_100,
    bitsPerSample: Int? = 16,
    channels: Int? = 2,
    sizeBytes: Long = 30_000_000,
    folder: String = "Music/Rock",
    hasArtwork: Boolean = true,
    tagsFilled: Int = 7,
    dateAddedSec: Long = 1_759_500_000,
    title: String = "Song",
) = DuplicateCopy(
    uri = "content://music/$name", title = title, artist = "Band", tagTitle = title, tagArtist = "Band", durationMs = 245_000,
    sizeBytes = sizeBytes, lastModifiedMs = 1, format = format, bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz,
    bitsPerSample = bitsPerSample, channelCount = channels, folderPath = folder, fileName = "$name.flac", hasArtwork = hasArtwork,
    tagsFilled = tagsFilled, dateAddedSec = dateAddedSec,
)

internal class TestLibrary(var files: List<DuplicateCopy>) : DuplicateLibrary {
    override suspend fun copies() = files
}

internal object NoHashes : ContentHasher {
    override suspend fun hash(uri: String): String? = null
}

internal class TestFingerprints : DuplicateFingerprints {
    override suspend fun all(): List<DuplicateFingerprint> = emptyList()

    override suspend fun update(fingerprints: List<DuplicateFingerprint>, gone: Collection<String>) = Unit
}

internal class TestDismissals : DuplicateDismissals {
    val sets = mutableListOf<Set<String>>()

    override suspend fun dismissed() = sets.toList()

    override suspend fun dismiss(uris: Set<String>) {
        sets += uris
    }
}

internal class TestAlerts : DuplicateAlerts {
    override val enabled = MutableStateFlow(true)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun lastNotifiedCount() = 0

    override suspend fun setLastNotifiedCount(count: Int) = Unit
}
