package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.rekordbox.manifest.ManifestFormatException
import io.sonora.core.rekordbox.manifest.StickManifest

/** What a stick holds before an export. */
sealed interface StickLibrary {
    /** No device library yet. */
    data object Empty : StickLibrary

    /**
     * A library Sonora exported: the export adds to it. [needsRebuild] when an earlier export stopped while it was
     * putting the library files in place: the next export rewrites them from the [manifest] even if it adds nothing.
     */
    data class Sonora(val manifest: StickManifest, val needsRebuild: Boolean = false) : StickLibrary

    /** An `export.pdb` without Sonora's manifest (exported by rekordbox, as a rule). It is replaced, not merged. */
    data object Foreign : StickLibrary

    /** Sonora's manifest is there but cannot be used (damaged, or written by a newer Sonora). */
    data class Unreadable(val cause: ManifestFormatException) : StickLibrary

    /** Exporting replaces this library, so the user has to agree first. */
    val needsConfirmation: Boolean get() = this is Foreign || this is Unreadable

    val trackCount: Int get() = (this as? Sonora)?.manifest?.tracks?.size ?: 0
}

/**
 * One export: the [tracks] (those the [profile]'s player does not take are converted on the way) and, when [playlistName] is not blank, the playlist
 * they join (the stick's playlist of that name, ignoring case, or a new one). [replaceUnknownLibrary] is the user's consent to replace a [StickLibrary.Foreign] or
 * [StickLibrary.Unreadable] library. [grants] is the read access kept for the tracks ([UriPermissions.takeRead]):
 * the [ExportRunner] that starts the export gives it up when the export ends.
 */
data class ExportRequest(
    val tracks: List<SourceFile>,
    val playlistName: String?,
    val replaceUnknownLibrary: Boolean = false,
    val profile: DeviceProfile = DeviceProfile.XDJ_700,
    val grants: Set<String> = emptySet(),
)

enum class ExportPhase {
    /** Converting a file the player does not take ([ExportConversion]); only for those, before the rest. */
    CONVERTING,

    /** Looking for the track on the stick, reading its tags. */
    PREPARING,
    ANALYSING,
    COPYING,

    /** Writing `export.pdb`, `exportExt.pdb`, `DEVSETTING.DAT` and the manifest, after every track. */
    WRITING_LIBRARY,
}

/** Where an export is: track [trackIndex] (from 0) of [trackCount], [trackFraction] (0..1) of the way through it. */
data class ExportProgress(
    val trackIndex: Int,
    val trackCount: Int,
    val trackName: String,
    val phase: ExportPhase,
    val trackFraction: Float = 0f,
) {
    val overallFraction: Float
        get() = when {
            phase == ExportPhase.WRITING_LIBRARY || trackCount == 0 -> 1f
            else -> ((trackIndex + trackFraction.coerceIn(0f, 1f)) / trackCount).coerceIn(0f, 1f)
        }
}

sealed interface ExportResult {

    /**
     * The export ran through its tracks. [playlist] is the playlist it made ([playlistCreated]) or added
     * [playlistAdded] tracks to, if any. The library was rewritten ([libraryWritten]) when tracks were added, a
     * playlist changed or tracks already there gained their cover and comment; it now holds [libraryTrackCount] tracks.
     */
    data class Completed(
        val exported: List<ExportedTrack>,
        val duplicates: List<DuplicateTrack>,
        val failed: List<FailedTrack>,
        val playlist: String?,
        val libraryTrackCount: Int,
        val libraryWritten: Boolean,
        val playlistAdded: Int = 0,
        val playlistCreated: Boolean = false,
    ) : ExportResult

    /** Nothing was written: the stick has [freeBytes] free and the export needs about [neededBytes]. */
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : ExportResult

    /** Nothing was written: [library] would be replaced and the request did not say the user agreed. */
    data class NeedsConfirmation(val library: StickLibrary) : ExportResult

    /**
     * The export stopped during [step]: the stick failed ([cause] a `StickIoException`) or something unexpected
     * happened. Unless [libraryChanged], the stick's previous library is untouched and the files this export had
     * written were removed (as far as the stick allowed). Once [libraryChanged], the library files were being put in
     * place: the copied files stay, the manifest may already list them, and the next export completes the library.
     */
    data class Failed(val step: String, val cause: Throwable, val libraryChanged: Boolean = false) : ExportResult

    /** The user cancelled: the previous library is untouched and this export's files were removed. */
    data object Cancelled : ExportResult
}

data class ExportedTrack(val name: String, val stickPath: String)

/** [name] is already on the stick at [existingPath] (same size and content) and was not copied again. */
data class DuplicateTrack(val name: String, val existingPath: String)

data class FailedTrack(val name: String, val reason: TrackFailure, val cause: Throwable)

/** Why one track was left out; the export goes on with the others. */
enum class TrackFailure {
    /** The file could not be opened or read (moved, deleted, access revoked, damaged). */
    UNREADABLE,

    /** The audio was read but no tempo or beat grid could be found (too short, beatless). */
    NOT_ANALYSABLE,

    /** The player does not take the file and it could not be converted: its audio could not be decoded. */
    NOT_CONVERTIBLE,

    /** The file's content changed while it was being exported (still being written or edited); export it again. */
    CHANGED_DURING_EXPORT,

    /** Anything else that went wrong preparing this track. */
    UNEXPECTED,
}
