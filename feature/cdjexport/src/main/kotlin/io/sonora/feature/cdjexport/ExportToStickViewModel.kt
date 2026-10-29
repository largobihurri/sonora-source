package io.sonora.feature.cdjexport

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.ExportSourceReader
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolumes
import io.sonora.core.domain.export.StickLocator
import io.sonora.core.domain.export.StickVolume
import io.sonora.core.domain.log.redactForLog
import io.sonora.feature.cdjexport.di.CdjExportIoDispatcher
import io.sonora.feature.cdjexport.export.ExportConversion
import io.sonora.feature.cdjexport.export.ExportJob
import io.sonora.feature.cdjexport.export.ExportRequest
import io.sonora.feature.cdjexport.export.ExportRunner
import io.sonora.feature.cdjexport.export.StickExporter
import io.sonora.feature.cdjexport.export.StickLibrary
import io.sonora.feature.cdjexport.export.StickShortcut
import io.sonora.feature.cdjexport.export.StickShortcuts
import io.sonora.feature.cdjexport.export.TrackPlan
import io.sonora.feature.cdjexport.export.UriPermissions
import io.sonora.feature.cdjexport.di.CdjExportClock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export to Rekordbox": pick tracks (files or a folder) and a stick, then export them with a device library.
 *
 * Picked files are checked against the device profile right away: those the player does not take are listed to be
 * converted on export; those that cannot be read or decoded are listed apart with why and never exported. The export itself runs in the app-wide [ExportRunner], so it goes on when
 * the screen rotates or the app goes to the background; this ViewModel only shows it.
 *
 * Read access to picked documents is kept ([UriPermissions.takeRead]) only while a listed track or a pick still being
 * looked at needs it. It is given up as soon as none does, when the screen goes, or, handed over with the request, by
 * the runner when the export ends.
 *
 * A request from another screen ([ExportRequests]) replaces the selection with its tracks and names the playlist after
 * it, once the screen is back to choosing tracks; it is consumed, so it is applied once. Nothing is exported until the
 * user says so.
 *
 * Sticks are reached without the system's file picker when possible ([StickShortcuts]): a mounted stick whose root
 * Sonora already has access to opens by itself while none is chosen; choosing a stick opens the one mounted stick with
 * that access, else the picker at its root.
 */
@HiltViewModel
class ExportToStickViewModel @Inject constructor(
    private val sources: ExportSourceReader,
    private val sticks: StickLocator,
    private val exporter: StickExporter,
    private val runner: ExportRunner,
    private val permissions: UriPermissions,
    @CdjExportIoDispatcher private val io: CoroutineDispatcher,
    @CdjExportClock private val clock: Clock,
    private val environment: AppEnvironment,
    private val requests: ExportRequests,
    private val volumes: RemovableVolumes,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportToStickState(playlistName = defaultPlaylistName(LocalDate.now(clock))))
    val state: StateFlow<ExportToStickState> = _state.asStateFlow()

    /** The opened stick behind [ExportToStickState.stick]. Main thread. */
    private var volume: StickVolume? = null

    /** Counts stick picks: only the latest one's result is shown. Main thread. */
    private var stickPicks = 0

    /** The stick being opened, cancelled by a newer pick. Main thread. */
    private var opening: Job? = null

    /** The storage tree [opening] opens. Main thread. */
    private var openingTree: String? = null

    /** Read access this screen kept and still owns (not handed to an export). Main thread. */
    private val grants = LinkedHashSet<String>()

    /** The grant each listed track relies on: its own URI, or the folder it was picked from. Main thread. */
    private val trackGrants = HashMap<String, String>()

    /** Picks still being looked at. Main thread. */
    private val picks = mutableListOf<Pick>()

    /** The screen is gone: nothing needs access any more. Main thread. */
    private var cleared = false

    /** The storage trees Sonora holds read and write access to, as last read. Main thread. */
    private var heldTrees: Set<String> = emptySet()

    /** The volume being ejected: not opened by itself again until Android lets go of it. Main thread. */
    private var ejecting: String? = null

    /** Gives up access off the main thread, also once [viewModelScope] has stopped. */
    private val releaser = CoroutineScope(SupervisorJob() + io)

    /** One pick of files or a folder; [kept] is filled on the I/O dispatcher. */
    private class Pick(val grant: List<String>) {
        val kept: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var job: Job? = null
    }

    init {
        viewModelScope.launch { runner.job.collect(::onJobChanged) }
        viewModelScope.launch {
            // A request waits while an export is confirmed, runs or shows its result.
            val choosing = _state.map { it.step == ExportStep.Setup }.distinctUntilChanged()
            combine(requests.pending, choosing) { request, setup -> request.takeIf { setup } }
                .filterNotNull()
                .collect(::onExportRequested)
        }
        viewModelScope.launch { volumes.volumes().collect(::onVolumesChanged) }
    }

    fun onFilesPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        addPicks(grant = uris, folder = null) { uris }
    }

    /** Adds the audio files anywhere under [treeUri]; the tree's grant covers them all. */
    fun onFolderPicked(treeUri: String) {
        addPicks(grant = listOf(treeUri), folder = treeUri) { sources.listAudioFiles(treeUri) }
    }

    fun removeTrack(uri: String) {
        if (_state.value.step != ExportStep.Setup) return
        _state.update { it.copy(tracks = it.tracks.filterNot { track -> track.uri == uri }) }
        trackGrants.remove(uri)
        releaseUnneeded()
    }

    /** Empties the selection, picks still being looked at included. */
    fun clearTracks() {
        if (_state.value.step != ExportStep.Setup) return
        picks.forEach { it.job?.cancel() }
        _state.update { it.copy(tracks = emptyList(), rejected = emptyList()) }
        trackGrants.clear()
        releaseUnneeded()
    }

    /**
     * "Choose stick": opens the one mounted stick Sonora has access to, else asks for the picker at its root; with none
     * or several mounted, the picker opens where it likes (several are listed to choose from instead).
     */
    fun chooseStick(): StickPick {
        val only = _state.value.attached.singleOrNull() ?: return StickPick.Picker(sticks.pickerStartUri())
        return chooseVolume(only.uuid)
    }

    /** Opens the mounted volume [uuid] with the access Sonora holds, else asks for the picker at its root. */
    fun chooseVolume(uuid: String): StickPick {
        val volume = _state.value.attached.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?: return StickPick.Picker(sticks.pickerStartUri())
        return when (val shortcut = StickShortcuts.forVolume(volume, heldTrees)) {
            is StickShortcut.Open -> {
                onStickPicked(shortcut.treeUri)
                StickPick.Opened
            }
            else -> StickPick.PickerAt(volume.uuid)
        }
    }

    fun onStickPicked(treeUri: String) {
        if (_state.value.step != ExportStep.Setup) return
        val pick = ++stickPicks
        opening?.cancel()
        _state.update { it.copy(openingStick = true) }
        openingTree = treeUri
        opening = viewModelScope.launch {
            try {
                val (opened, choice) = withContext(io) { openStick(treeUri) }
                // A grant that could not be kept is gone: the next choice of this stick goes through the picker.
                heldTrees = if (choice.problem == StickProblem.NO_ACCESS) heldTrees - treeUri else heldTrees + treeUri
                if (pick == stickPicks) {
                    volume = opened
                    _state.update { it.copy(stick = choice) }
                }
            } finally {
                if (pick == stickPicks) {
                    openingTree = null
                    _state.update { it.copy(openingStick = false) }
                }
            }
        }
    }

    /**
     * Lets go of the chosen stick, or of the one still opening, before it is ejected: nothing of it stays open (the
     * export writes and closes each file; the stick's listing cache goes with it), and it does not open by itself again
     * until Android has let go of it. Returns its volume id, the one to eject (`null` when none was chosen or it is not
     * a volume's tree). A running export keeps its stick: nothing is let go.
     */
    fun forgetStick(): String? {
        if (_state.value.step is ExportStep.Running) return null
        val tree = _state.value.stick?.treeUri ?: openingTree
        stickPicks++
        opening?.cancel()
        opening = null
        openingTree = null
        volume = null
        ejecting = tree?.let(StickShortcuts::volumeOf)
        _state.update { it.copy(stick = null, openingStick = false) }
        return ejecting
    }

    fun onPlaylistNameChange(text: String) = _state.update { it.copy(playlistName = text) }

    /** Starts the export, or first asks to replace a library Sonora did not make. */
    fun startExport() {
        val current = _state.value
        if (!current.canExport) return
        val library = current.stick?.library ?: return
        if (library.needsConfirmation) {
            _state.update { it.copy(step = ExportStep.ConfirmReplace(library), notice = null) }
        } else {
            start(replaceUnknownLibrary = false)
        }
    }

    fun confirmReplace() {
        if (_state.value.step !is ExportStep.ConfirmReplace) return
        _state.update { it.copy(step = ExportStep.Setup) }
        start(replaceUnknownLibrary = true)
    }

    fun dismissReplace() {
        if (_state.value.step is ExportStep.ConfirmReplace) _state.update { it.copy(step = ExportStep.Setup) }
    }

    fun cancelExport() {
        if (_state.value.step is ExportStep.Running) runner.cancel()
    }

    /** Leaves the result: the next export starts from an empty selection on the same stick. */
    fun finish() {
        if (_state.value.step !is ExportStep.Done) return
        runner.acknowledge()
        trackGrants.clear()
        _state.update {
            it.copy(
                tracks = emptyList(),
                rejected = emptyList(),
                playlistName = defaultPlaylistName(LocalDate.now(clock)),
                step = ExportStep.Setup,
                notice = null,
            )
        }
        releaseUnneeded()
        val stick = _state.value.stick
        if (stick != null) onStickPicked(stick.treeUri) else openOnlyStick()
    }

    override fun onCleared() {
        cleared = true
        // Picks still being looked at give theirs up when they stop (see addPicks).
        releaseUnneeded()
    }

    /** Lists [request]'s tracks instead of the selection, for one playlist named after it. */
    private fun onExportRequested(request: ExportPlaylistRequest) {
        // Not pending any more: a newer request replaced it (and comes next), or another screen took it.
        if (!requests.consume(request)) return
        clearTracks()
        _state.update { it.copy(playlistName = request.name, notice = null) }
        addPicks(grant = request.trackUris, folder = null) { request.trackUris }
    }

    /** Hands the tracks and their access to the runner, or says why it did not take them. */
    private fun start(replaceUnknownLibrary: Boolean) {
        val current = _state.value
        val stick = volume ?: return
        val request = ExportRequest(
            tracks = current.tracks.map { it.file },
            playlistName = current.playlistName.trim().ifEmpty { null },
            replaceUnknownLibrary = replaceUnknownLibrary,
            profile = current.profile,
            grants = grants.toSet(),
        )
        if (runner.start(stick, request)) {
            grants.clear()
            _state.update { it.copy(notice = null) }
        } else {
            _state.update { it.copy(notice = ExportNotice.ANOTHER_EXPORT_RUNNING) }
        }
    }

    /**
     * Keeps read access to [grant], then looks at the documents [list] gives (on the I/O dispatcher) and sorts them
     * into tracks and rejected files. [folder] is the picked folder, when it is one. A pick cleared before it is done
     * adds nothing.
     */
    private fun addPicks(grant: List<String>, folder: String?, list: suspend () -> List<String>) {
        if (_state.value.step != ExportStep.Setup) return
        val pick = Pick(grant)
        picks += pick
        _state.update { it.copy(inspecting = true) }
        pick.job = viewModelScope.launch {
            try {
                withContext(io) { keepAccess(pick) }
                val profile = _state.value.profile
                val (tracks, rejected) = withContext(io) { inspect(list(), profile) }
                ensureActive()
                add(tracks, rejected, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Looking at the picked ${if (folder != null) "folder" else "files"} failed", e)
                val unreadable = if (folder != null) {
                    listOf(RejectedTrack(folder, displayName(folder), RejectReason.UnreadableFolder))
                } else {
                    grant.map { RejectedTrack(it, displayName(it), RejectReason.UnreadableFile) }
                }
                add(emptyList(), unreadable, folder)
            } finally {
                picks -= pick
                grants += pick.kept
                if (picks.isEmpty()) _state.update { it.copy(inspecting = false) }
                releaseUnneeded()
            }
        }
    }

    /** Blocking: keeps read access to each of the pick's documents; one that cannot be kept is only logged. */
    private fun keepAccess(pick: Pick) {
        for (uri in pick.grant) {
            try {
                if (permissions.takeRead(uri)) pick.kept += uri
            } catch (e: Exception) {
                // The picker's own grant may still do; looking at the file tells.
                Log.w(TAG, "Keeping access to ${redactForLog(uri)} failed", e)
            }
        }
    }

    /** Lists what a pick found after what is listed already; a file listed before is not listed twice. */
    private fun add(tracks: List<PickedTrack>, rejected: List<RejectedTrack>, folder: String?) {
        val current = _state.value
        val known = current.tracks.map { it.uri }.toSet() + current.rejected.map { it.uri }
        val newTracks = tracks.filter { it.uri !in known }
        newTracks.forEach { trackGrants[it.uri] = folder ?: it.uri }
        _state.update {
            it.copy(tracks = it.tracks + newTracks, rejected = it.rejected + rejected.filter { r -> r.uri !in known })
        }
    }

    private suspend fun inspect(
        uris: List<String>,
        profile: DeviceProfile,
    ): Pair<List<PickedTrack>, List<RejectedTrack>> {
        val tracks = mutableListOf<PickedTrack>()
        val rejected = mutableListOf<RejectedTrack>()
        for (uri in uris.distinct()) {
            val file = try {
                sources.inspect(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read picked file ${redactForLog(uri)}", e)
                rejected += RejectedTrack(uri, displayName(uri), RejectReason.UnreadableFile)
                continue
            }
            when (ExportConversion.plan(file.audio, profile)) {
                TrackPlan.AsIs -> tracks += PickedTrack(file)
                is TrackPlan.Convert -> tracks += PickedTrack(file, converts = true)
                TrackPlan.Undecodable ->
                    rejected += RejectedTrack(uri, file.fileName, RejectReason.Undecodable(file.audio.container))
            }
        }
        return tracks to rejected
    }

    /** Blocking: keeps access to the stick, opens it and reads its library and free space. */
    private fun openStick(treeUri: String): Pair<StickVolume?, StickChoice> {
        val kept = try {
            permissions.takeReadWrite(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot keep access to the picked stick ${redactForLog(treeUri)}", e)
            false
        }
        // Without kept access the export would lose the stick once the picker's grant ends.
        if (!kept) return null to StickChoice(treeUri, null, null, StickLibrary.Empty, StickProblem.NO_ACCESS)
        val opened = try {
            sticks.open(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the picked stick ${redactForLog(treeUri)}", e)
            return null to StickChoice(treeUri, null, null, StickLibrary.Empty, StickProblem.UNUSABLE)
        }
        if (!opened.isRemovableRoot) {
            return null to StickChoice(treeUri, opened.name, null, StickLibrary.Empty, StickProblem.NOT_A_STICK)
        }
        return try {
            val library = exporter.readLibrary(opened.fileSystem)
            opened to StickChoice(treeUri, opened.name, opened.fileSystem.freeBytes(), library)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the stick ${redactForLog(treeUri)}", e)
            null to StickChoice(treeUri, opened.name, null, StickLibrary.Empty, StickProblem.UNUSABLE)
        }
    }

    private suspend fun onVolumesChanged(all: List<RemovableVolume>) {
        val writable = all.filter { it.isWritable }
        // Android let go of the ejected volume: plugged in again, it may open by itself.
        if (writable.none { it.uuid.equals(ejecting, ignoreCase = true) }) ejecting = null
        if (writable.isNotEmpty()) {
            heldTrees = try {
                withContext(io) { permissions.heldReadWriteTrees() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read the storage access Sonora holds", e)
                emptySet()
            }
        }
        _state.update { it.copy(attached = writable) }
        openOnlyStick()
    }

    /** Opens the one mounted stick Sonora has access to, while none is chosen and tracks are being chosen. */
    private fun openOnlyStick() {
        val current = _state.value
        if (current.step != ExportStep.Setup || current.stick != null || current.openingStick) return
        val shortcut = StickShortcuts.choose(current.attached, heldTrees) as? StickShortcut.Open ?: return
        if (shortcut.volume.uuid.equals(ejecting, ignoreCase = true)) return
        onStickPicked(shortcut.treeUri)
    }

    private fun onJobChanged(job: ExportJob) {
        val step = when (job) {
            ExportJob.Idle -> return
            is ExportJob.Running -> ExportStep.Running(job.stickName, job.trackCount, job.progress, job.cancelling)
            is ExportJob.Finished ->
                ExportStep.Done(job.result, exportTechnicalReport(environment, job.stickName, job.result))
        }
        _state.update { it.copy(step = step) }
    }

    /** Gives up the access this screen owns that no listed track and no pick still being looked at needs. */
    private fun releaseUnneeded() {
        val needed = if (cleared) emptySet() else trackGrants.values.toSet() + picks.flatMap { it.grant }
        val unneeded = grants.filterNot { it in needed }
        if (unneeded.isEmpty()) return
        grants.removeAll(unneeded.toSet())
        releaser.launch {
            unneeded.forEach { uri ->
                try {
                    permissions.release(uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Releasing access to ${redactForLog(uri)} failed", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SonoraExport"
    }
}

/** The last part of a document or tree URI, for a list: "House" for `…/tree/primary%3AMusic%2FHouse`. */
internal fun displayName(uri: String): String {
    val last = uri.substringAfterLast('/')
    val decoded = try {
        URLDecoder.decode(last.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        last
    }
    return decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { decoded }
}
