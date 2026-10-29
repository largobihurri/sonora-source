package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.CachedTrackAnalyzer
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.export.ExportArtwork
import io.sonora.core.domain.export.ExportSourceReader
import io.sonora.core.domain.export.ExportText
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.domain.export.StickFileSystem
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.domain.export.StickPathPlanner
import io.sonora.core.rekordbox.anlz.AnlzPaths
import io.sonora.core.rekordbox.anlz.AnlzTrack
import io.sonora.core.rekordbox.anlz.AnlzWriter
import io.sonora.core.rekordbox.artwork.ArtworkPaths
import io.sonora.core.rekordbox.manifest.ManifestArtwork
import io.sonora.core.rekordbox.manifest.ManifestFormatException
import io.sonora.core.rekordbox.manifest.ManifestJson
import io.sonora.core.rekordbox.manifest.ManifestPlaylist
import io.sonora.core.rekordbox.manifest.ManifestTrack
import io.sonora.core.rekordbox.manifest.StickManifest
import io.sonora.core.rekordbox.pdb.DeviceFileType
import io.sonora.core.rekordbox.pdb.PdbTemplates
import io.sonora.core.rekordbox.pdb.PdbWriter
import io.sonora.core.rekordbox.setting.DevSettingWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import io.sonora.feature.cdjexport.di.CdjExportClock
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Exports tracks to a stick with a Rekordbox device library, adding to what Sonora exported there before.
 *
 * Each track in turn: a file the player does not take is first converted ([ExportConversion], [converter]) to a
 * temporary file, which stands for it from then on (its tags are still read from the original) and is deleted once the
 * track is done, failed or cancelled; one the decoder cannot read is reported. Then its content is hashed (SHA-256); a track already on the stick (same size and hash) is not copied
 * again but still joins the new playlist; otherwise its tags are read, it is analysed (or its analysis is taken from the
 * analysis cache, by that hash: audio analysed for any earlier export is not decoded again), copied to
 * `/Contents/<Artist>/<Album>/<file>` and its `.DAT`/`.EXT` written to the `PIONEER/USBANLZ` folder the players compute
 * from that path ([AnlzPaths.folderFor]), numbered after the files other tracks already have there.
 *
 * Covers and comments: a track's comment comes from its tags; its cover is its embedded front cover, else the picture
 * the library shows for it ([ExportArtwork.libraryPicture], its folder's image), written as rekordbox does to
 * `PIONEER/Artwork/<folder>/aN.jpg` (80x80) and `aN_m.jpg` (240x240) ([ArtworkPaths]). Tracks whose pictures hash the
 * same share one; picture files the library does not list are never overwritten. A track already on the stick that an
 * older Sonora listed without looking for its cover and comment ([ManifestTrack.extrasRead]) gains them, and its genre
 * if it had none, when it is exported again: its audio is not copied again.
 *
 * Analysis files an earlier Sonora export put in other folders (players never look there) are first copied to the
 * players' folders and the library rewritten to point at them; once the new library is in place, the old files and
 * the analysis files in every analysis folder (`Pxxx/xxxxxxxx`) no track of the library points to are removed (the
 * folder too once that leaves it empty).
 * Then, only if something changed (or an earlier export stopped half-way through the library, see below), the whole
 * library is rebuilt from the updated manifest in two stages:
 * 1. `export.pdb`, `exportExt.pdb`, `DEVSETTING.DAT` and the manifest are each written to a temporary file
 *    (`<name>.sonora-new`) next to the real one;
 * 2. the temporary files replace the real ones, the manifest first.
 *
 * The manifest goes first because it is what the next export rebuilds from: once it is in place it lists every track
 * this export copied, so a swap that stops before `export.pdb` is replaced is completed by the next export, which
 * sees the temporary files left behind ([StickLibrary.Sonora.needsRebuild]) and rewrites the library even if it adds
 * nothing. A manifest lost between the two halves of its own replace (delete, then rename) is read back from its
 * complete temporary copy.
 *
 * Failures:
 * - a track that cannot be read, decoded or analysed is reported ([FailedTrack]) and the export goes on;
 * - so is a track whose content changed while it was exported (its copy does not hash as it did first, see "Reading
 *   the sources"): its copy and analysis files are removed and its cache entry dropped, so a stale analysis reaches
 *   neither the stick nor a later export. Exporting it again, once the file is stable, analyses it afresh;
 * - any other failure ([StickIoException]: removed, full, access revoked; or anything unexpected) stops the export
 *   ([ExportResult.Failed]). Before stage 2 has started, whatever this export wrote is removed and the previous
 *   library stays; from then on ([ExportResult.Failed.libraryChanged]) the copied files stay, since the manifest may
 *   already list them, and the next export completes the library;
 * - cancellation (of the calling coroutine) is honoured until the library is written, which then runs to its end; it
 *   removes this export's files and rethrows.
 * Files the export did not put there (another library's audio or analysis files) are never overwritten. Before the
 * tracks, the empty folders Android creates at the root of a new volume are removed ([AndroidStandardFolders]).
 *
 * Converting again a file exported before gives the same bytes (the converter's dither is seeded by the source), so
 * it is found on the stick as a duplicate, after its conversion.
 *
 * Reading the sources: a new track is read in full up to three times, to hash it, to decode it (on a cache miss) and
 * to copy it, which hashes it again. The two hashes bracket the decode: when they match, the analysis cached under
 * that hash is of the bytes copied (short of the file changing and changing back in between). The first read costs
 * little CPU (SHA-256 runs at about 1.8 GB/s on a desktop JVM; hardware-accelerated on ARMv8 phones), so its cost is
 * the I/O: for a 5-minute 16-bit AIFF (53 MB) about 0.1 to 0.3 s from internal storage (often from the page cache on
 * the next read), but 1.5 to 3 s from a source on USB at 20 to 40 MB/s, next to a copy to the stick of 2 to 5 s.
 * Hashing only while copying would save that read but not bracket the decode any more (and would copy a duplicate
 * before finding it is one); analysing the stick copy instead would need a decodable URI for it and would copy
 * tracks that then fail analysis. Neither is worth the risk for that time, so the extra read stays.
 *
 * DJ data: a track Sonora's DJ mode has analysed ([dj]) gets the grid in force (the user's edit included), its hot cues,
 * memory cues and loops and the colour waveforms in its analysis files, plus a `.2EX` file with the 3-band waveform,
 * and the library lists that grid's tempo and the DJ key ([DjAnlzMapper]); a track without DJ data is written exactly
 * as before. Times move into rekordbox's time base for the file on the stick (an AIFF here, so by nothing).
 *
 * Blocking stick and source I/O runs on the caller's dispatcher: call it from an I/O dispatcher.
 */
class StickExporter @Inject constructor(
    private val sources: ExportSourceReader,
    private val analyzer: CachedTrackAnalyzer,
    @CdjExportClock private val clock: Clock,
    private val converter: ExportTrackConverter,
    private val artwork: ExportArtwork = ExportArtwork.NONE,
    private val dj: DjExportSource = DjExportSource.NONE,
) {

    /**
     * What [stick] holds now. A damaged manifest (any [ManifestFormatException]) is [StickLibrary.Unreadable]: the
     * export then needs the user's consent, since it can only replace that library, never add to it.
     * Blocking; @throws StickIoException
     */
    fun readLibrary(stick: StickFileSystem): StickLibrary {
        val manifest = stick.read(StickManifest.PATH)
        val interrupted = LIBRARY_FILES.any { stick.exists(it + TEMP_SUFFIX) }
        if (manifest == null && interrupted) recoveredManifest(stick)?.let { return StickLibrary.Sonora(it, true) }
        return when {
            manifest != null -> try {
                val missing = LIBRARY_FILES.any { it != StickManifest.PATH && !stick.exists(it) }
                StickLibrary.Sonora(ManifestJson.decode(manifest), needsRebuild = interrupted || missing)
            } catch (e: ManifestFormatException) {
                StickLibrary.Unreadable(e)
            }
            stick.exists(PDB_PATH) -> StickLibrary.Foreign
            else -> StickLibrary.Empty
        }
    }

    /**
     * The manifest an earlier export had written in full but lost while replacing the old one (deleted, not yet
     * renamed); `null` when its temporary copy is missing or unreadable.
     */
    private fun recoveredManifest(stick: StickFileSystem): StickManifest? {
        val bytes = stick.read(StickManifest.PATH + TEMP_SUFFIX) ?: return null
        return try {
            ManifestJson.decode(bytes)
        } catch (e: ManifestFormatException) {
            null
        }
    }

    /** Exports [request] to [stick], reporting [onProgress] as it goes. Cancellable until the library is written. */
    suspend fun export(
        stick: StickFileSystem,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val library = try {
            readLibrary(stick)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExportResult.Failed(STEP_READING, e)
        }
        if (library.needsConfirmation && !request.replaceUnknownLibrary) return ExportResult.NeedsConfirmation(library)
        val sonora = library as? StickLibrary.Sonora
        val manifest = sonora?.manifest ?: StickManifest()
        val knownSizes = manifest.tracks.map { it.fileSize }.toSet()
        // A file that goes as it is and has the size of one on the stick is likely that one; a converted one counts at
        // the size it will have.
        val newBytes = request.tracks.sumOf { track ->
            val asIs = ExportConversion.plan(track.audio, request.profile) == TrackPlan.AsIs
            if (asIs && track.sizeBytes in knownSizes) 0L else ExportConversion.exportBytes(track, request.profile)
        }
        val needed = requiredBytes(newBytes, request.tracks.size, manifest.tracks.size)
        val free = try {
            stick.freeBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExportResult.Failed(STEP_READING, e)
        }
        if (free != null && free < needed) return ExportResult.NotEnoughSpace(needed, free)
        // Android's empty standard folders (Alarms, DCIM, ...): best effort, never a reason to stop.
        AndroidStandardFolders.removeEmpty(stick)
        // Conversions an export killed with the app left behind; best effort too.
        try {
            converter.discardLeftovers()
        } catch (e: Exception) {
            // They go with the next export, or with the app's cache.
        }

        val run = Run(stick, request, manifest, rebuild = sonora?.needsRebuild == true, onProgress)
        return try {
            run.moveAnalysisToPlayersFolders()
            request.tracks.forEachIndexed { index, source -> run.track(index, source) }
            run.finish()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { run.removeCopiedFiles() }
            throw e
        } catch (e: Exception) {
            // A stick failure or anything unexpected: the same clean-up either way.
            if (!run.libraryChanged) run.removeCopiedFiles()
            ExportResult.Failed(run.step, e, run.libraryChanged)
        }
    }

    /** The state of one export as it goes through its tracks. */
    private inner class Run(
        private val stick: StickFileSystem,
        private val request: ExportRequest,
        private var manifest: StickManifest,
        /** The library files must be rewritten even if this export adds nothing (an earlier swap was interrupted). */
        private val rebuild: Boolean,
        private val onProgress: (ExportProgress) -> Unit,
    ) {
        private val today: LocalDate = LocalDate.now(clock)
        private val planner = StickPathPlanner(manifest.tracks.map { it.filePath })
        /** The `analyze_path` of every track, upper-cased: the analysis files taken. */
        private val analysisFiles = manifest.tracks.map { key(it.analyzePath) }.toMutableSet()

        /** Analysis files of earlier exports that were copied to the players' folders, to delete once the library is in place. */
        private val staleAnalysis = mutableListOf<String>()

        /** Files this export created, from the stick root, so they can go again if it does not finish. */
        private val copied = mutableListOf<String>()
        private val playlistIds = mutableListOf<Int>()
        private var playlistAdded = 0
        private var playlistCreated = false
        private val exported = mutableListOf<ExportedTrack>()
        private val duplicates = mutableListOf<DuplicateTrack>()
        private val failed = mutableListOf<FailedTrack>()

        /** Tracks already on the stick that gained their cover and comment ([fillExtras]). */
        private var filled = 0

        /** Tracks already on the stick whose analysis files or library tempo and key changed ([refreshAnalysis]). */
        private var refreshed = 0

        /** What the export was doing, for a failure report. */
        var step: String = STEP_READING
            private set

        /**
         * The library files on the stick are being replaced (stage 2 has started): a failure from here on may leave
         * a library whose manifest already lists this export's files, so they stay.
         */
        var libraryChanged = false
            private set

        suspend fun track(index: Int, source: SourceFile) {
            currentCoroutineContext().ensureActive()
            val plan = ExportConversion.plan(source.audio, request.profile)
            // A converted track spends the first part of its progress converting, the rest as any other.
            val start = if (plan is TrackPlan.Convert) CONVERSION_SHARE else 0f
            fun progress(phase: ExportPhase, fraction: Float) {
                val trackFraction = if (phase == ExportPhase.CONVERTING) fraction * start else start + fraction * (1 - start)
                onProgress(ExportProgress(index, request.tracks.size, source.fileName, phase, trackFraction))
            }
            val createdBefore = copied.size
            var converted: ConvertedTrack? = null
            try {
                val file = when (plan) {
                    TrackPlan.AsIs -> source
                    TrackPlan.Undecodable -> throw NotConvertibleException(source.fileName, null)
                    is TrackPlan.Convert -> {
                        step = "Converting ${source.fileName}"
                        progress(ExportPhase.CONVERTING, 0f)
                        convert(source) { progress(ExportPhase.CONVERTING, it.coerceIn(0f, 1f)) }
                            .also { converted = it }
                            .file
                    }
                }
                progress(ExportPhase.PREPARING, 0f)
                step = "Preparing ${source.fileName}"
                val contentSha256 = sources.openInput(file.uri).use { sha256(it) }
                val existing = findOnStick(file, contentSha256)
                if (existing != null) {
                    duplicates += DuplicateTrack(source.fileName, existing.filePath)
                    playlistIds += existing.id
                    if (!existing.extrasRead) fillExtras(existing, source)
                    refreshAnalysis(existing.id, source, file, contentSha256)
                    return
                }
                val tags = sources.readTags(source.uri)
                step = "Analysing ${source.fileName}"
                progress(ExportPhase.ANALYSING, 0f)
                val analysis = analyzer.analyze(file.uri, contentSha256) {
                    progress(ExportPhase.ANALYSING, it.coerceIn(0f, 1f) / 2)
                }
                val path = planner.plan(tags.artist, tags.album, file.fileName) { stick.exists(it.removePrefix("/")) }
                step = "Copying ${source.fileName} to $path"
                progress(ExportPhase.COPYING, 0.5f)
                val copy = copy(file, path) { progress(ExportPhase.COPYING, 0.5f + it / 2) }
                if (copy.sha256 != contentSha256) {
                    // The analysis (cached or fresh, keyed by the first hash) may be of other bytes than the copy's.
                    analyzer.forget(contentSha256)
                    throw SourceChangedException(source.fileName)
                }
                step = "Writing the analysis of ${source.fileName}"
                val anlz = analysisPaths(path)
                // The DJ data belongs to the library track (the original), not to a temporary converted file.
                val djData = djDataOf(source)
                val anlzTrack = DjAnlzMapper.anlzTrack(
                    path, analysis, djData, StickAudio(DeviceFileType.AIFF, 0, file.audio.sampleRateHz ?: DEFAULT_SAMPLE_RATE),
                )
                write(anlz.datPath, AnlzWriter.writeDat(anlzTrack))
                write(anlz.extPath, AnlzWriter.writeExt(anlzTrack))
                AnlzWriter.writeEx2(anlzTrack)?.let { write(anlz.ex2Path, it) }
                val artworkId = artworkOf(source, tags)
                add(file, tags, analysis, djData, path, anlz.analyzePath, copy, artworkId)
                exported += ExportedTrack(source.fileName, path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StickIoException) {
                throw e
            } catch (e: Exception) {
                failed += FailedTrack(source.fileName, reasonOf(e), e)
                removeFiles(copied.subList(createdBefore, copied.size))
            } finally {
                // The temporary file goes whatever happened: copied, failed, cancelled or the stick gone.
                converted?.close()
            }
        }

        /**
         * Brings the analysis files of the track already on the stick (library track [trackId]) up to date with the
         * DJ data [source] has now: its grid in force, cues, loops and colour waveform. A track Sonora's DJ mode has
         * not analysed is left as it is. Each file is replaced only when its bytes differ, through a temporary file,
         * so an interrupted update keeps the old file; the library is rewritten when the tempo or key changed. An
         * analysis that cannot be had leaves the track as it was. Blocking; @throws StickIoException
         */
        private suspend fun refreshAnalysis(trackId: Int, source: SourceFile, file: SourceFile, contentSha256: String) {
            val track = manifest.tracks.firstOrNull { it.id == trackId } ?: return
            val djData = djDataOf(source) ?: return
            step = "Updating the analysis of ${source.fileName}"
            val analysis = try {
                analyzer.analyze(file.uri, contentSha256) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            val anlzTrack = DjAnlzMapper.anlzTrack(
                track.filePath, analysis, djData,
                StickAudio(track.fileType, 0, track.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE),
            )
            val dat = track.analyzePath.removePrefix("/")
            val stem = dat.substringBeforeLast('.')
            var changed = replaceIfChanged(dat, AnlzWriter.writeDat(anlzTrack))
            changed = replaceIfChanged("$stem.EXT", AnlzWriter.writeExt(anlzTrack)) || changed
            AnlzWriter.writeEx2(anlzTrack)?.let { changed = replaceIfChanged("$stem.2EX", it) || changed }
            val bpm = DjAnlzMapper.tempoBpm(analysis, djData)
            val key = DjAnlzMapper.key(analysis, djData)
            if (bpm != track.bpm || key != track.key) {
                val updated = track.copy(bpm = bpm, key = key)
                manifest = manifest.copy(tracks = manifest.tracks.map { if (it.id == trackId) updated else it })
                changed = true
            }
            if (changed) refreshed++
        }

        /** Replaces [path] with [bytes] through a temporary file unless it holds them already; whether it changed. */
        private fun replaceIfChanged(path: String, bytes: ByteArray): Boolean {
            if (onStick("Reading $path") { stick.read(path) }?.contentEquals(bytes) == true) return false
            val temp = path + TEMP_SUFFIX
            write(temp, bytes)
            onStick("Replacing $path") { stick.replace(temp, path) }
            copied -= temp
            return true
        }

        /**
         * Fills in the comment and cover of [track], listed by an older Sonora without them, from its picked [source]
         * (and its genre, when it had none). Its audio and analysis stay as they are. Tags that cannot be read now
         * leave the track as it was, to be looked at again by the next export: it is still on the stick (a duplicate),
         * never a failed track. A stick failure while writing its cover stops the export as any other.
         */
        private suspend fun fillExtras(track: ManifestTrack, source: SourceFile) {
            step = "Reading the tags of ${source.fileName}"
            val tags = try {
                sources.readTags(source.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            val updated = track.copy(
                comment = ExportText.clamp(tags.comment),
                artworkId = artworkOf(source, tags),
                genre = track.genre ?: ExportText.clamp(tags.genre),
                extrasRead = true,
            )
            manifest = manifest.copy(tracks = manifest.tracks.map { if (it.id == track.id) updated else it })
            filled++
        }

        /**
         * The id of [source]'s cover on the stick: its embedded cover ([tags]), else the library's picture for it, as
         * the players' two JPEGs. A picture already on the stick that hashes the same is shared; a new one is written
         * under the next id whose files are free. `null` when there is no picture or it does not decode.
         * Blocking; @throws StickIoException
         */
        private suspend fun artworkOf(source: SourceFile, tags: SourceTags): Int? {
            val images = try {
                val picture = tags.cover?.bytes ?: artwork.libraryPicture(source.uri) ?: return null
                artwork.deviceImages(picture) ?: return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cover is never worth a track.
                return null
            }
            val hash = (images.large + images.small).sha256Hex()
            manifest.artwork.firstOrNull { it.sha256 == hash }?.let { return it.id }
            var id = manifest.nextArtworkId
            fun small() = ArtworkPaths.small(id).removePrefix("/")
            fun large() = ArtworkPaths.large(id).removePrefix("/")
            while (stick.exists(small()) || stick.exists(large())) id++
            step = "Writing the cover of ${source.fileName}"
            write(large(), images.large)
            write(small(), images.small)
            manifest = manifest.copy(artwork = manifest.artwork + ManifestArtwork(id, hash))
            return id
        }

        /** [source] converted for the request's player; a decoding failure is reported as not convertible. */
        private suspend fun convert(source: SourceFile, onFraction: (Float) -> Unit): ConvertedTrack = try {
            converter.convert(source, request.profile, onFraction)
        } catch (e: PcmDecodeException) {
            if (e is PcmDecodeException.SourceUnavailable) throw e
            throw NotConvertibleException(source.fileName, e)
        }

        /**
         * Copies the analysis files of library tracks that are not in the folder the players compute from their path
         * (written by an earlier Sonora) to that folder, and points the tracks at the copies. A track whose files cannot
         * be read keeps its old path. Blocking.
         */
        fun moveAnalysisToPlayersFolders() {
            val tracks = manifest.tracks.map { track ->
                val current = track.analyzePath.removePrefix("/")
                if (key(current.substringBeforeLast('/')) == key(AnlzPaths.folderFor(track.filePath))) return@map track
                step = "Moving the analysis of ${track.filePath}"
                val oldExt = current.substringBeforeLast('.') + ".EXT"
                val dat = onStick("Reading $current") { stick.read(current) }
                val ext = onStick("Reading $oldExt") { stick.read(oldExt) }
                if (dat == null || ext == null) return@map track
                val paths = analysisPaths(track.filePath)
                write(paths.datPath, dat)
                write(paths.extPath, ext)
                staleAnalysis += listOf(current, oldExt)
                track.copy(analyzePath = paths.analyzePath)
            }
            manifest = manifest.copy(tracks = tracks)
        }

        /** Writes the library from the updated manifest, if this export changed anything. */
        fun finish(): ExportResult {
            val playlistName = ExportText.clamp(request.playlistName, MAX_PLAYLIST_CHARS)
                ?.takeIf { playlistIds.isNotEmpty() }
                ?.let(::addToPlaylist)
            val changed = exported.isNotEmpty() || playlistName != null || rebuild || staleAnalysis.isNotEmpty() ||
                filled > 0 || refreshed > 0
            if (changed) {
                onProgress(ExportProgress(request.tracks.size, request.tracks.size, "", ExportPhase.WRITING_LIBRARY, 1f))
                step = "Building the library"
                // Everything is built before the first library file is touched, so a refused row changes nothing.
                val pdb = try {
                    PdbWriter.write(manifest.toDeviceLibrary(today))
                } catch (e: IllegalArgumentException) {
                    throw StickIoException("The library could not be built: ${e.message}", e)
                }
                val files = mapOf(
                    PDB_PATH to pdb,
                    EXT_PDB_PATH to PdbTemplates.exportExtPdb(),
                    DEVSETTING_PATH to DevSettingWriter.write(),
                    StickManifest.PATH to ManifestJson.encode(manifest),
                )
                // Stage 1: every file next to the real one. The manifest last, so a complete temporary manifest means
                // the other temporary files are complete too.
                files.forEach { (path, bytes) ->
                    step = "Writing $path"
                    write(path + TEMP_SUFFIX, bytes)
                }
                // Stage 2: the manifest first, so the next export can always rebuild what follows from it.
                libraryChanged = true
                LIBRARY_FILES.forEach { path ->
                    step = "Replacing $path"
                    stick.replace(path + TEMP_SUFFIX, path)
                }
                removeUnusedAnalysis()
            }
            return ExportResult.Completed(
                exported = exported.toList(),
                duplicates = duplicates.toList(),
                failed = failed.toList(),
                playlist = playlistName,
                libraryTrackCount = manifest.tracks.size,
                libraryWritten = changed,
                playlistAdded = playlistAdded,
                playlistCreated = playlistCreated,
            )
        }

        /** Best effort: the stick may be gone already. */
        fun removeCopiedFiles() = removeFiles(copied)

        /**
         * Once the library is in place: deletes the moved analysis files, then the analysis files (`ANLZnnnn.DAT`,
         * `.EXT`, `.2EX`) directly in every `Pxxx/xxxxxxxx` folder under `PIONEER/USBANLZ` that no track of the library
         * points to, and the folders that leaves empty. Anything else there (other files, subfolders) is kept, and so
         * is the folder holding it. Best effort, whatever the failure: the library is already in place, and a file left
         * behind costs space, never the library.
         */
        private fun removeUnusedAnalysis() {
            removeFiles(staleAnalysis)
            val used = manifest.tracks.map { key(it.analyzePath.removePrefix("/").substringBeforeLast('/')) }.toSet()
            try {
                for (outer in stick.list(ANLZ_ROOT).orEmpty()) {
                    if (!outer.isFolder || !OUTER_FOLDER.matches(outer.name)) continue
                    val outerPath = "$ANLZ_ROOT/${outer.name}"
                    for (inner in stick.list(outerPath).orEmpty()) {
                        val innerPath = "$outerPath/${inner.name}"
                        if (!inner.isFolder || !INNER_FOLDER.matches(inner.name) || key(innerPath) in used) continue
                        stick.list(innerPath).orEmpty()
                            .filter { !it.isFolder && ANALYSIS_FILE.matches(it.name) }
                            .forEach { stick.delete("$innerPath/${it.name}") }
                        if (stick.list(innerPath)?.isEmpty() == true) stick.delete(innerPath)
                    }
                    if (stick.list(outerPath)?.isEmpty() == true) stick.delete(outerPath)
                }
            } catch (e: Exception) {
                // Best effort, as above.
            }
        }

        /** A track of the library (this export's included) with the same size and content, if there is one. */
        private fun findOnStick(source: SourceFile, contentSha256: String): ManifestTrack? =
            manifest.tracks.firstOrNull { it.fileSize == source.sizeBytes && it.contentSha256 == contentSha256 }

        /** Copies [source] to [path] (from the root, with a leading `/`); its size and SHA-256 as copied. */
        private suspend fun copy(source: SourceFile, path: String, onFraction: (Float) -> Unit): Copied {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            sources.openInput(source.uri).use { input ->
                val target = path.removePrefix("/")
                copied += target
                onStick("Writing $target") { stick.openWrite(target) }.useOnStick(target) { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            throw SourceReadException(e)
                        }
                        if (read < 0) break
                        try {
                            output.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw e as? StickIoException ?: StickIoException("Writing $target failed", e)
                        }
                        digest.update(buffer, 0, read)
                        size += read
                        if (source.sizeBytes > 0) onFraction((size.toFloat() / source.sizeBytes).coerceAtMost(1f))
                    }
                }
            }
            return Copied(size, digest.digest().toHex())
        }

        /**
         * The track's analysis files in the folder the players compute from [audioPath]: the first number no track of
         * the library and no other file on the stick takes.
         */
        private fun analysisPaths(audioPath: String): AnlzPaths {
            var number = 0
            while (true) {
                val paths = AnlzPaths.forAudioPath(audioPath, number)
                val free = key(paths.analyzePath) !in analysisFiles &&
                    !stick.exists(paths.datPath) && !stick.exists(paths.extPath)
                if (free) {
                    analysisFiles += key(paths.analyzePath)
                    return paths
                }
                number++
            }
        }

        /** Sonora's DJ data for [source], `null` when it has none; whatever goes wrong with it, the track still exports. */
        private suspend fun djDataOf(source: SourceFile): DjTrackExport? = try {
            dj.dataFor(source.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        private fun add(
            source: SourceFile,
            tags: SourceTags,
            analysis: TrackAnalysis,
            djData: DjTrackExport?,
            path: String,
            analyzePath: String,
            copy: Copied,
            artworkId: Int?,
        ) {
            val track = ManifestTrack(
                id = manifest.nextTrackId,
                title = ExportText.clamp(tags.title)
                    ?: ExportText.clamp(source.fileName.substringBeforeLast('.'))
                    ?: source.fileName,
                artist = ExportText.clamp(tags.artist),
                album = ExportText.clamp(tags.album),
                genre = ExportText.clamp(tags.genre),
                trackNumber = ExportText.trackNumber(tags.trackNumber),
                year = ExportText.year(tags.year),
                filePath = path,
                analyzePath = analyzePath,
                fileSize = copy.size,
                contentSha256 = copy.sha256,
                sampleRate = source.audio.sampleRateHz ?: 0,
                bitDepth = source.audio.bitsPerSample ?: 0,
                channelCount = source.audio.channelCount ?: 2,
                durationSeconds = ((analysis.durationMs + MS_PER_SECOND / 2) / MS_PER_SECOND).toInt(),
                bpm = DjAnlzMapper.tempoBpm(analysis, djData),
                key = DjAnlzMapper.key(analysis, djData),
                dateAdded = today,
                analyzerVersion = analysis.analyzerVersion,
                comment = ExportText.clamp(tags.comment),
                artworkId = artworkId,
            )
            manifest = manifest.copy(tracks = manifest.tracks + track)
            playlistIds += track.id
        }

        private fun write(path: String, bytes: ByteArray) {
            copied += path
            onStick("Writing $path") { stick.openWrite(path).use { it.write(bytes) } }
        }

        /**
         * Adds this export's tracks to the playlist called [name] (ignoring case), keeping its name and date, or to a
         * new one. The name of the playlist that changed, or null when every track was already in it.
         */
        private fun addToPlaylist(name: String): String? {
            val index = manifest.playlists.indexOfFirst { key(it.name) == key(name) }
            if (index < 0) {
                val playlist = ManifestPlaylist(name, playlistIds.distinct(), created = today)
                manifest = manifest.copy(playlists = manifest.playlists + playlist)
                playlistAdded = playlist.trackIds.size
                playlistCreated = true
                return name
            }
            val existing = manifest.playlists[index]
            val added = playlistIds.distinct() - existing.trackIds.toSet()
            if (added.isEmpty()) return null
            val playlists = manifest.playlists.toMutableList()
            playlists[index] = existing.copy(trackIds = existing.trackIds + added)
            manifest = manifest.copy(playlists = playlists)
            playlistAdded = added.size
            return existing.name
        }

        private fun removeFiles(paths: MutableList<String>) {
            for (path in paths) {
                try {
                    stick.delete(path)
                } catch (e: Exception) {
                    // Best effort: an orphan file costs space, never the library.
                }
            }
            paths.clear()
        }
    }

    private class Copied(val size: Long, val sha256: String)

    /** [use] for a stick file: a failure to close it (flushing its last bytes) is the stick's too. */
    private inline fun OutputStream.useOnStick(path: String, block: (OutputStream) -> Unit) {
        try {
            block(this)
        } catch (e: Throwable) {
            try {
                close()
            } catch (suppressed: IOException) {
                e.addSuppressed(suppressed)
            }
            throw e
        }
        onStick("Writing $path") { close() }
    }

    /** The picked file's content as copied is not what was hashed (and analysed) before: it changed meanwhile. */
    private class SourceChangedException(name: String) : Exception("$name changed while it was being exported")

    /** The player does not take the picked file and its audio cannot be decoded to convert it. */
    private class NotConvertibleException(name: String, cause: Throwable?) :
        Exception("$name cannot be decoded to convert it", cause)

    /** Reading the picked file failed while copying it (not the stick). */
    private class SourceReadException(cause: IOException) : IOException("Reading the source failed", cause)

    companion object {
        const val PDB_PATH = "PIONEER/rekordbox/export.pdb"
        const val EXT_PDB_PATH = "PIONEER/rekordbox/exportExt.pdb"
        const val DEVSETTING_PATH = "PIONEER/DEVSETTING.DAT"

        /** The library files in the order they are put in place: the manifest first (see the class comment). */
        private val LIBRARY_FILES = listOf(StickManifest.PATH, PDB_PATH, EXT_PDB_PATH, DEVSETTING_PATH)

        /** Suffix of the temporary file a library file is written to before it replaces the real one. */
        const val TEMP_SUFFIX = ".sonora-new"

        private const val STEP_READING = "Reading the stick"
        private const val ANLZ_ROOT = "PIONEER/USBANLZ"
        private val OUTER_FOLDER = Regex("P[0-9A-Fa-f]{3}")
        private val INNER_FOLDER = Regex("[0-9A-Fa-f]{8}")
        private val ANALYSIS_FILE = Regex("ANLZ[0-9]{4}\\.(DAT|EXT|2EX)", RegexOption.IGNORE_CASE)
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MS_PER_SECOND = 1000L
        private const val MAX_PLAYLIST_CHARS = 64

        /** The part of a converted track's progress its conversion takes (decoding, resampling and writing it). */
        private const val CONVERSION_SHARE = 0.4f

        /** Analysis files of one track (a 10-minute detail waveform is about 90 kB) plus FAT cluster slack. */
        private const val PER_TRACK_BYTES = 1L shl 20

        /** The library files, their temporary copies and room to spare. */
        private const val LIBRARY_BYTES = 4L shl 20

        /** Library and manifest bytes per track, counted twice for the temporary copies. */
        private const val PER_LIBRARY_TRACK_BYTES = 8L shl 10

        /** Room the export needs on a stick for [newTrackBytes] of audio in [trackCount] files. */
        fun requiredBytes(newTrackBytes: Long, trackCount: Int, libraryTrackCount: Int): Long =
            newTrackBytes + trackCount * PER_TRACK_BYTES + LIBRARY_BYTES +
                (libraryTrackCount + trackCount) * PER_LIBRARY_TRACK_BYTES

        /** Runs [block], reporting any I/O failure of it as the stick's. */
        private inline fun <T> onStick(what: String, block: () -> T): T = try {
            block()
        } catch (e: StickIoException) {
            throw e
        } catch (e: IOException) {
            throw StickIoException("$what failed", e)
        }

        private fun reasonOf(failure: Exception): TrackFailure = when (failure) {
            is AnalysisException -> TrackFailure.NOT_ANALYSABLE
            is SourceChangedException -> TrackFailure.CHANGED_DURING_EXPORT
            is NotConvertibleException -> TrackFailure.NOT_CONVERTIBLE
            is PcmDecodeException, is SourceReadException, is IOException, is SecurityException -> TrackFailure.UNREADABLE
            else -> TrackFailure.UNEXPECTED
        }

        /** The SHA-256 of what [input] holds, in lower-case hex; cancellable between blocks. */
        private suspend fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

        private fun key(text: String): String = text.uppercase(Locale.ROOT)
    }
}
