package io.sonora.feature.cdjexport

import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.SourceFile
import io.sonora.feature.cdjexport.export.ExportConversion
import io.sonora.feature.cdjexport.export.ExportProgress
import io.sonora.feature.cdjexport.export.ExportResult
import io.sonora.feature.cdjexport.export.StickExporter
import io.sonora.feature.cdjexport.export.StickLibrary
import io.sonora.feature.cdjexport.export.StickShortcuts

/**
 * Everything the "Export to Rekordbox" section shows, without text: the section words it in the app's language (see
 * ExportToStickText), so a language change applies to what is already on screen.
 */
data class ExportToStickState(
    val profile: DeviceProfile = DeviceProfile.XDJ_700,
    /** Picked files to export, in pick order; those the [profile]'s player does not take are converted on export. */
    val tracks: List<PickedTrack> = emptyList(),
    /** Picked files that cannot be exported (unreadable, or not decodable to convert them), with why. */
    val rejected: List<RejectedTrack> = emptyList(),
    /** Picked files are being looked at. */
    val inspecting: Boolean = false,
    val stick: StickChoice? = null,
    /** Opening the picked stick. */
    val openingStick: Boolean = false,
    /** The playlist the export adds its tracks to; blank for none. */
    val playlistName: String = "",
    val step: ExportStep = ExportStep.Setup,
    /** Why the last "Export" did nothing (another export is running), until the next attempt. */
    val notice: ExportNotice? = null,
    /** The removable volumes mounted for writing now (sticks, or an SD card), to choose from by name and size. */
    val attached: List<RemovableVolume> = emptyList(),
) {
    /** The size of the chosen [stick]'s volume, while it is mounted and the system says. */
    val stickSize: Long?
        get() {
            val uuid = stick?.let { StickShortcuts.volumeOf(it.treeUri) } ?: return null
            return attached.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.totalBytes
        }

    /** What the picked files take where they are. */
    val totalBytes: Long get() = tracks.sumOf { it.file.sizeBytes }

    /** How many of the [tracks] are converted on export. */
    val convertCount: Int get() = tracks.count { it.converts }

    /** About how much room the export needs on the [stick], converted tracks at the size they will have. */
    val neededBytes: Long
        get() = StickExporter.requiredBytes(
            tracks.sumOf { ExportConversion.exportBytes(it.file, profile) },
            tracks.size,
            stick?.library?.trackCount ?: 0,
        )

    /** The [stick] says how much room it has, and it is too little for the tracks. */
    val lacksSpace: Boolean
        get() {
            val free = stick?.freeBytes ?: return false
            return tracks.isNotEmpty() && free < neededBytes
        }

    val canExport: Boolean
        get() = step == ExportStep.Setup && tracks.isNotEmpty() && !inspecting && !openingStick &&
            stick != null && stick.problem == null && !lacksSpace
}

/** A file to export; [converts] when the player does not take it as it is. */
data class PickedTrack(val file: SourceFile, val converts: Boolean = false) {
    val name: String get() = file.fileName
    val uri: String get() = file.uri
}

data class RejectedTrack(val uri: String, val name: String, val reason: RejectReason)

/** Why a picked file is not listed for export. */
sealed interface RejectReason {
    /** The picked file could not be read (moved, deleted, access revoked). */
    data object UnreadableFile : RejectReason

    /** The picked folder could not be listed. */
    data object UnreadableFolder : RejectReason

    /** The player does not take the file and Sonora cannot decode its audio (a [container] it has no decoder for). */
    data class Undecodable(val container: AudioContainer) : RejectReason
}

/** Why the last "Export" did nothing. */
enum class ExportNotice {
    ANOTHER_EXPORT_RUNNING,
}

/** Why the picked storage cannot be exported to. */
enum class StickProblem {
    /** Android did not let the app keep access to it. */
    NO_ACCESS,

    /** It is a folder or the phone's own storage, not the top of a USB stick. */
    NOT_A_STICK,

    /** It could not be opened or read. */
    UNUSABLE,
}

/**
 * The picked stick: its [name] (`null` when it could not be opened), free space (`null` when unknown) and the [library]
 * already on it. [problem] says why it cannot be exported to (not the top of a USB stick, unreadable).
 */
data class StickChoice(
    val treeUri: String,
    val name: String?,
    val freeBytes: Long?,
    val library: StickLibrary,
    val problem: StickProblem? = null,
)

/** What choosing a stick asks the screen to do. */
sealed interface StickPick {
    /** Nothing: the stick was opened with the access Sonora already holds. */
    data object Opened : StickPick

    /** Open the system's folder picker at the root of the volume [uuid] (one tap from "Use this folder"). */
    data class PickerAt(val uuid: String) : StickPick

    /** Open the system's folder picker, at [initialUri] when there is one. */
    data class Picker(val initialUri: String?) : StickPick
}

sealed interface ExportStep {
    data object Setup : ExportStep

    /** The stick holds a library Sonora would replace; the user has to agree first. */
    data class ConfirmReplace(val library: StickLibrary) : ExportStep

    data class Running(
        val stickName: String,
        val trackCount: Int,
        val progress: ExportProgress?,
        val cancelling: Boolean,
    ) : ExportStep

    /** The export ended with [result]; [technicalDetails] backs "Copy technical details" when something failed. */
    data class Done(val result: ExportResult, val technicalDetails: String?) : ExportStep
}
