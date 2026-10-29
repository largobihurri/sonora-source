package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.rekordbox.anlz.AnlzPaths
import io.sonora.core.rekordbox.anlz.AnlzTrack
import io.sonora.core.rekordbox.anlz.AnlzWriter
import io.sonora.core.rekordbox.manifest.ManifestJson
import io.sonora.core.rekordbox.manifest.StickManifest
import io.sonora.core.rekordbox.pdb.PdbTemplates
import io.sonora.core.rekordbox.pdb.PdbWriter
import io.sonora.core.rekordbox.setting.DevSettingWriter
import io.sonora.feature.cdjexport.export.StickExporter.Companion.DEVSETTING_PATH
import io.sonora.feature.cdjexport.export.StickExporter.Companion.EXT_PDB_PATH
import io.sonora.feature.cdjexport.export.StickExporter.Companion.PDB_PATH
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StickExporterTest {

    private val today = LocalDate.of(2026, 10, 3)
    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val analyzer = FakeAnalyzer()
    private val cache = FakeAnalysisCache()
    private val stick = FakeStickFileSystem()
    private val converter = FakeConverter(sources)
    private val exporter = StickExporter(sources, analyzer.cached(cache), clock, converter)

    private val flac = SourceAudio(AudioContainer.FLAC, 24, 96_000, 2, durationMs = 10_000)

    private val daftPunk = SourceTags(title = "One More Time", artist = "Daft Punk", album = "Discovery", year = 2001)

    private suspend fun export(vararg tracks: SourceFile, playlist: String? = null, replace: Boolean = false) =
        exporter.export(stick, ExportRequest(tracks.toList(), playlist, replaceUnknownLibrary = replace))

    private fun manifest(): StickManifest = ManifestJson.decode(stick.bytes(StickManifest.PATH)!!)

    @Test
    fun `a first export writes the audio, the analysis files and the library`() = runTest {
        val one = sources.add("01 One More Time.aiff", tags = daftPunk)

        val result = export(one) as ExportResult.Completed

        val audio = "Contents/Daft Punk/Discovery/01 One More Time.aiff"
        val anlz = AnlzPaths.forAudioPath("/$audio")
        assertThat(stick.paths).containsExactly(
            audio, anlz.datPath, anlz.extPath, PDB_PATH, EXT_PDB_PATH, DEVSETTING_PATH, StickManifest.PATH,
        )
        assertThat(stick.bytes(audio)).isEqualTo(sources.files.getValue(one.uri).bytes)
        assertThat(result.exported).containsExactly(ExportedTrack("01 One More Time.aiff", "/$audio"))
        assertThat(result.libraryWritten).isTrue()
        assertThat(result.libraryTrackCount).isEqualTo(1)
        val track = manifest().tracks.single()
        assertThat(track.title).isEqualTo("One More Time")
        assertThat(track.year).isEqualTo(2001)
        assertThat(track.analyzePath).isEqualTo(anlz.analyzePath)
        assertThat(track.key).isEqualTo(MusicalKey.A_MINOR)
        assertThat(track.bpm).isEqualTo(128.0)
        assertThat(track.durationSeconds).isEqualTo(10)
        assertThat(track.contentSha256).hasLength(64)
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
        assertThat(stick.bytes(EXT_PDB_PATH)).isEqualTo(PdbTemplates.exportExtPdb())
        assertThat(stick.bytes(DEVSETTING_PATH)).isEqualTo(DevSettingWriter.write())
    }

    @Test
    fun `untagged files fall back to their name and rekordbox's unknown folders`() = runTest {
        export(sources.add("Mystery Track.aiff"))

        assertThat(stick.exists("Contents/UnknownArtist/UnknownAlbum/Mystery Track.aiff")).isTrue()
        assertThat(manifest().tracks.single().title).isEqualTo("Mystery Track")
        assertThat(manifest().tracks.single().artist).isNull()
    }

    @Test
    fun `bad tags are cleaned instead of stopping the export`() = runTest {
        val tags = SourceTags(title = "t".repeat(3000), artist = "A/B:C", year = 70_000, trackNumber = -1)

        val result = export(sources.add("x.aiff", tags = tags)) as ExportResult.Completed

        assertThat(result.failed).isEmpty()
        val track = manifest().tracks.single()
        assertThat(track.title.length).isAtMost(256)
        assertThat(track.year).isNull()
        assertThat(track.trackNumber).isNull()
        assertThat(track.filePath).startsWith("/Contents/A_B_C/")
    }

    @Test
    fun `the library files are written last, through temporary files, and the manifest is put in place first`() = runTest {
        export(sources.add("a.aiff"), sources.add("b.aiff"))

        val writes = stick.log.filter { it.startsWith("write ") || it.startsWith("replace ") }
        val firstLibraryWrite = writes.indexOfFirst { "PIONEER/rekordbox" in it || "PIONEER/sonora" in it }
        assertThat(writes.take(firstLibraryWrite).all { "Contents/" in it || "USBANLZ" in it }).isTrue()
        assertThat(writes.drop(firstLibraryWrite)).containsExactly(
            "write $PDB_PATH.sonora-new",
            "write $EXT_PDB_PATH.sonora-new",
            "write $DEVSETTING_PATH.sonora-new",
            "write ${StickManifest.PATH}.sonora-new",
            "replace ${StickManifest.PATH}.sonora-new -> ${StickManifest.PATH}",
            "replace $PDB_PATH.sonora-new -> $PDB_PATH",
            "replace $EXT_PDB_PATH.sonora-new -> $EXT_PDB_PATH",
            "replace $DEVSETTING_PATH.sonora-new -> $DEVSETTING_PATH",
        ).inOrder()
        assertThat(stick.paths.none { it.endsWith(".sonora-new") }).isTrue()
    }

    @Test
    fun `a library swap stopped after the manifest is completed by the next export`() = runTest {
        export(sources.add("a.aiff"))
        stick.onReplace = { if (it == PDB_PATH) throw StickIoException("Stick removed") }

        val failed = export(sources.add("b.aiff")) as ExportResult.Failed

        assertThat(failed.libraryChanged).isTrue()
        assertThat(manifest().tracks.map { it.title }).containsExactly("a", "b").inOrder()
        assertThat(stick.exists("Contents/UnknownArtist/UnknownAlbum/b.aiff")).isTrue()
        assertThat((exporter.readLibrary(stick) as StickLibrary.Sonora).needsRebuild).isTrue()

        stick.onReplace = {}
        export(sources.add("c.aiff"))

        assertThat(manifest().tracks.map { it.title }).containsExactly("a", "b", "c").inOrder()
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
        assertThat(stick.paths.none { it.endsWith(".sonora-new") }).isTrue()
        assertThat((exporter.readLibrary(stick) as StickLibrary.Sonora).needsRebuild).isFalse()
    }

    @Test
    fun `an interrupted swap is completed even by an export that adds nothing`() = runTest {
        val bytes = ByteArray(2000) { 3 }
        export(sources.add("a.aiff", bytes = bytes))
        stick.onReplace = { if (it == EXT_PDB_PATH) throw StickIoException("Stick removed") }
        export(sources.add("b.aiff"))
        stick.onReplace = {}

        val again = export(sources.add("again.aiff", bytes = bytes, uri = "content://x/again")) as ExportResult.Completed

        assertThat(again.exported).isEmpty()
        assertThat(again.libraryWritten).isTrue()
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
        assertThat(stick.bytes(EXT_PDB_PATH)).isEqualTo(PdbTemplates.exportExtPdb())
        assertThat(stick.paths.none { it.endsWith(".sonora-new") }).isTrue()
    }

    @Test
    fun `a manifest lost half-way through its replace is taken from its complete temporary copy`() = runTest {
        export(sources.add("a.aiff"))
        stick.onReplace = {
            if (it == StickManifest.PATH) {
                stick.delete(StickManifest.PATH)
                throw StickIoException("Renaming failed")
            }
        }
        val failed = export(sources.add("b.aiff")) as ExportResult.Failed
        stick.onReplace = {}

        assertThat(failed.libraryChanged).isTrue()
        assertThat(stick.exists(StickManifest.PATH)).isFalse()
        val library = exporter.readLibrary(stick) as StickLibrary.Sonora
        assertThat(library.manifest.tracks.map { it.title }).containsExactly("a", "b").inOrder()

        export(sources.add("c.aiff"))

        assertThat(manifest().tracks.map { it.title }).containsExactly("a", "b", "c").inOrder()
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `a stick failure while the temporary library files are written keeps the previous library`() = runTest {
        export(sources.add("a.aiff"))
        val before = stick.snapshot()
        stick.failWrites = { it == "$DEVSETTING_PATH.sonora-new" }

        val result = export(sources.add("b.aiff")) as ExportResult.Failed

        assertThat(result.libraryChanged).isFalse()
        assertThat(result.step).contains(DEVSETTING_PATH)
        assertThat(stick.snapshot()).isEqualTo(before)
    }

    @Test
    fun `an unexpected failure in the library phase is reported like a stick failure, not thrown`() = runTest {
        export(sources.add("a.aiff"))
        val before = stick.snapshot()
        stick.onOpenWrite = { if (it == "$PDB_PATH.sonora-new") throw IllegalStateException("provider died") }

        val early = export(sources.add("b.aiff")) as ExportResult.Failed

        assertThat(early.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(early.libraryChanged).isFalse()
        assertThat(stick.snapshot()).isEqualTo(before)

        stick.onOpenWrite = {}
        stick.onReplace = { if (it == PDB_PATH) throw IllegalStateException("provider died") }

        val late = export(sources.add("b.aiff")) as ExportResult.Failed

        assertThat(late.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(late.libraryChanged).isTrue()
        assertThat(late.step).contains(PDB_PATH)
        assertThat(stick.exists("Contents/UnknownArtist/UnknownAlbum/b.aiff")).isTrue()
        assertThat(manifest().tracks.map { it.title }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `every track gets its own analysis folder`() = runTest {
        val tracks = (1..40).map { sources.add("Track $it.aiff") }

        export(*tracks.toTypedArray())

        val analyzePaths = manifest().tracks.map { it.analyzePath }
        assertThat(analyzePaths.toSet()).hasSize(40)
        analyzePaths.forEach { assertThat(stick.exists(it.removePrefix("/"))).isTrue() }
    }

    @Test
    fun `an analysis folder already on the stick is never overwritten`() = runTest {
        val taken = AnlzPaths.forAudioPath("/Contents/UnknownArtist/UnknownAlbum/a.aiff")
        stick.put(taken.datPath, byteArrayOf(1, 2, 3))

        export(sources.add("a.aiff"))

        assertThat(stick.bytes(taken.datPath)).isEqualTo(byteArrayOf(1, 2, 3))
        val track = manifest().tracks.single()
        assertThat(track.analyzePath).isNotEqualTo(taken.analyzePath)
        assertThat(stick.exists(track.analyzePath.removePrefix("/"))).isTrue()
    }

    @Test
    fun `analysis files go where the players look for them, with the track's path inside`() = runTest {
        export(sources.add("a.aiff"))

        val track = manifest().tracks.single()
        val folder = AnlzPaths.folderFor(track.filePath)
        assertThat(track.analyzePath).isEqualTo("/$folder/ANLZ0000.DAT")
        val dat = stick.bytes("$folder/ANLZ0000.DAT")!!
        assertThat(dat).isEqualTo(AnlzWriter.writeDat(AnlzTrack(track.filePath, FakeAnalyzer.ANALYSIS.beatGrid, FakeAnalyzer.ANALYSIS.waveform)))
        assertThat(stick.exists("$folder/ANLZ0000.EXT")).isTrue()
    }

    @Test
    fun `tracks whose paths share a folder get numbered analysis files`() = runTest {
        // Both paths hash to the same rekordbox folder (see AnlzPathsTest).
        export(sources.add("9306.aiff"), sources.add("10001.aiff"))

        val (first, second) = manifest().tracks
        val folder = AnlzPaths.folderFor(first.filePath)
        assertThat(AnlzPaths.folderFor(second.filePath)).isEqualTo(folder)
        assertThat(first.analyzePath).isEqualTo("/$folder/ANLZ0000.DAT")
        assertThat(second.analyzePath).isEqualTo("/$folder/ANLZ0001.DAT")
        assertThat(stick.bytes("$folder/ANLZ0001.DAT"))
            .isEqualTo(AnlzWriter.writeDat(AnlzTrack(second.filePath, FakeAnalyzer.ANALYSIS.beatGrid, FakeAnalyzer.ANALYSIS.waveform)))

        // A later export keeps both numbers.
        export(sources.add("9306.aiff", bytes = ByteArray(3000) { 7 }))

        val tracks = manifest().tracks
        assertThat(tracks.take(2).map { it.analyzePath }).containsExactly(first.analyzePath, second.analyzePath).inOrder()
    }

    @Test
    fun `analysis files of an earlier Sonora export are moved to the players' folders`() = runTest {
        export(sources.add("a.aiff"), sources.add("b.aiff"))
        // Rewrite the stick as an earlier Sonora left it: analysis in folders named after the path's SHA-256.
        val old = manifest()
        val moved = old.tracks.map { track ->
            val legacy = "PIONEER/USBANLZ/P2B5/${track.id.toString().padStart(8, '0')}"
            val current = track.analyzePath.removePrefix("/")
            stick.put("$legacy/ANLZ0000.DAT", stick.bytes(current)!!)
            stick.put("$legacy/ANLZ0000.EXT", stick.bytes(current.removeSuffix(".DAT") + ".EXT")!!)
            stick.delete(current)
            stick.delete(current.removeSuffix(".DAT") + ".EXT")
            track.copy(analyzePath = "/$legacy/ANLZ0000.DAT")
        }
        val legacyManifest = old.copy(tracks = moved)
        stick.put(StickManifest.PATH, ManifestJson.encode(legacyManifest))
        stick.put(PDB_PATH, PdbWriter.write(legacyManifest.toDeviceLibrary(today)))
        val legacyDat = moved.associate { it.filePath to stick.bytes(it.analyzePath.removePrefix("/"))!! }

        val result = export(sources.add("c.aiff")) as ExportResult.Completed

        assertThat(result.libraryWritten).isTrue()
        val tracks = manifest().tracks
        tracks.forEach { track ->
            assertThat(track.analyzePath).isEqualTo("/${AnlzPaths.folderFor(track.filePath)}/ANLZ0000.DAT")
            assertThat(stick.exists(track.analyzePath.removePrefix("/"))).isTrue()
        }
        tracks.take(2).forEach { assertThat(stick.bytes(it.analyzePath.removePrefix("/"))).isEqualTo(legacyDat[it.filePath]) }
        assertThat(stick.paths.filter { "P2B5" in it }).isEmpty()
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `an export with nothing new still moves old analysis files`() = runTest {
        val a = sources.add("a.aiff")
        export(a)
        val track = manifest().tracks.single()
        val current = track.analyzePath.removePrefix("/")
        val legacy = "PIONEER/USBANLZ/P2B5/0000ABCD/ANLZ0000.DAT"
        stick.put(legacy, stick.bytes(current)!!)
        stick.put(legacy.removeSuffix(".DAT") + ".EXT", stick.bytes(current.removeSuffix(".DAT") + ".EXT")!!)
        stick.delete(current)
        stick.delete(current.removeSuffix(".DAT") + ".EXT")
        stick.put(StickManifest.PATH, ManifestJson.encode(manifest().copy(tracks = listOf(track.copy(analyzePath = "/$legacy")))))

        val result = export(a) as ExportResult.Completed

        assertThat(result.duplicates).hasSize(1)
        assertThat(result.libraryWritten).isTrue()
        assertThat(manifest().tracks.single().analyzePath).isEqualTo(track.analyzePath)
        assertThat(stick.exists(current)).isTrue()
        assertThat(stick.exists(legacy)).isFalse()
    }

    /** [a] exported, then its analysis put back where an earlier Sonora kept it; the legacy `.DAT` path. */
    private suspend fun legacyStick(a: SourceFile): String {
        export(a)
        val track = manifest().tracks.single()
        val current = track.analyzePath.removePrefix("/")
        val legacy = "PIONEER/USBANLZ/P2B5/0000ABCD/ANLZ0000.DAT"
        stick.put(legacy, stick.bytes(current)!!)
        stick.put(legacy.removeSuffix(".DAT") + ".EXT", stick.bytes(current.removeSuffix(".DAT") + ".EXT")!!)
        stick.delete(current)
        stick.delete(current.removeSuffix(".DAT") + ".EXT")
        stick.put(StickManifest.PATH, ManifestJson.encode(manifest().copy(tracks = listOf(track.copy(analyzePath = "/$legacy")))))
        return legacy
    }

    @Test
    fun `old analysis files that cannot all be read stay where they are, and so does the track's path`() = runTest {
        val a = sources.add("a.aiff")
        val legacy = legacyStick(a)
        stick.delete(legacy.removeSuffix(".DAT") + ".EXT")

        val result = export(a) as ExportResult.Completed

        assertThat(result.libraryWritten).isFalse()
        assertThat(manifest().tracks.single().analyzePath).isEqualTo("/$legacy")
        assertThat(stick.exists(legacy)).isTrue()
    }

    @Test
    fun `a stick failure while moving old analysis files keeps the old library and files, and removes the copies`() = runTest {
        val a = sources.add("a.aiff")
        val legacy = legacyStick(a)
        val before = stick.snapshot()
        val target = AnlzPaths.folderFor(manifest().tracks.single().filePath)
        stick.failWrites = { it.startsWith(target) && it.endsWith(".EXT") }

        val result = export(a)

        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
        assertThat((result as ExportResult.Failed).libraryChanged).isFalse()
        assertThat(stick.snapshot()).isEqualTo(before)
        assertThat(stick.exists(legacy)).isTrue()
    }

    @Test
    fun `analysis folders no track points to are removed, other files never`() = runTest {
        stick.put("PIONEER/USBANLZ/P123/00ABCDEF/ANLZ0000.DAT", byteArrayOf(1))
        stick.put("PIONEER/USBANLZ/P123/00ABCDEF/ANLZ0000.EXT", byteArrayOf(2))
        stick.put("PIONEER/USBANLZ/notes.txt", byteArrayOf(3))
        stick.put("PIONEER/USBANLZ/Mine/keep.DAT", byteArrayOf(4))

        export(sources.add("a.aiff"))

        assertThat(stick.paths.filter { "P123" in it }).isEmpty()
        assertThat(stick.exists("PIONEER/USBANLZ/notes.txt")).isTrue()
        assertThat(stick.exists("PIONEER/USBANLZ/Mine/keep.DAT")).isTrue()
        assertThat(stick.exists(manifest().tracks.single().analyzePath.removePrefix("/"))).isTrue()
    }

    @Test
    fun `in an analysis folder no track points to only the analysis files go`() = runTest {
        val orphan = "PIONEER/USBANLZ/P123/00ABCDEF"
        stick.put("$orphan/ANLZ0000.DAT", byteArrayOf(1))
        stick.put("$orphan/anlz0001.ext", byteArrayOf(2))
        stick.put("$orphan/ANLZ0000.2EX", byteArrayOf(3))
        stick.put("$orphan/notes.txt", byteArrayOf(4))
        stick.put("$orphan/Set/ANLZ0000.DAT", byteArrayOf(5))
        stick.put("PIONEER/USBANLZ/P456/0000FFFF/Mine.DAT", byteArrayOf(6))

        export(sources.add("a.aiff"))

        assertThat(stick.paths.filter { it.startsWith("$orphan/") })
            .containsExactly("$orphan/notes.txt", "$orphan/Set/ANLZ0000.DAT")
        assertThat(stick.exists("PIONEER/USBANLZ/P456/0000FFFF/Mine.DAT")).isTrue()
    }

    @Test
    fun `a clean-up that fails in an unexpected way after the library is in place still completes the export`() = runTest {
        stick.put("PIONEER/USBANLZ/P123/00ABCDEF/ANLZ0000.DAT", byteArrayOf(1))
        val odd = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun delete(path: String): Boolean =
                if (path.startsWith("PIONEER/USBANLZ/P123")) throw IllegalStateException("provider gone") else stick.delete(path)
        }

        val result = exporter.export(odd, ExportRequest(listOf(sources.add("a.aiff")), null))

        assertThat(result).isInstanceOf(ExportResult.Completed::class.java)
        assertThat(manifest().tracks).hasSize(1)
    }

    @Test
    fun `an audio file already on the stick that the library does not know is never overwritten`() = runTest {
        stick.put("Contents/UnknownArtist/UnknownAlbum/a.aiff", byteArrayOf(9))

        export(sources.add("a.aiff"))

        assertThat(stick.bytes("Contents/UnknownArtist/UnknownAlbum/a.aiff")).isEqualTo(byteArrayOf(9))
        assertThat(manifest().tracks.single().filePath).isEqualTo("/Contents/UnknownArtist/UnknownAlbum/a (2).aiff")
    }

    @Test
    fun `a second export adds its tracks to the ones already on the stick`() = runTest {
        export(sources.add("a.aiff"), sources.add("b.aiff"))
        val firstAudio = stick.bytes("Contents/UnknownArtist/UnknownAlbum/a.aiff")

        val result = export(sources.add("c.aiff")) as ExportResult.Completed

        assertThat(manifest().tracks.map { it.id to it.title }).containsExactly(1 to "a", 2 to "b", 3 to "c").inOrder()
        assertThat(result.libraryTrackCount).isEqualTo(3)
        assertThat(stick.bytes("Contents/UnknownArtist/UnknownAlbum/a.aiff")).isEqualTo(firstAudio)
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `a track already on the stick is not copied again but joins the new playlist`() = runTest {
        val bytes = ByteArray(5000) { (it * 7).toByte() }
        export(sources.add("a.aiff", bytes = bytes))
        val sameAgain = sources.add("a copy.aiff", bytes = bytes, uri = "content://elsewhere/a")
        val sameSizeOtherContent = sources.add("b.aiff", bytes = ByteArray(5000) { 1 })
        analyzer.analysed.clear()

        val result = export(sameAgain, sameSizeOtherContent, playlist = "Friday") as ExportResult.Completed

        assertThat(result.duplicates).containsExactly(DuplicateTrack("a copy.aiff", "/Contents/UnknownArtist/UnknownAlbum/a.aiff"))
        assertThat(result.exported.map { it.name }).containsExactly("b.aiff")
        assertThat(analyzer.analysed).containsExactly(sameSizeOtherContent.uri)
        assertThat(stick.exists("Contents/UnknownArtist/UnknownAlbum/a copy.aiff")).isFalse()
        assertThat(manifest().tracks).hasSize(2)
        assertThat(manifest().playlists.single().name).isEqualTo("Friday")
        assertThat(manifest().playlists.single().trackIds).containsExactly(1, 2).inOrder()
        assertThat(manifest().playlists.single().created).isEqualTo(today)
        assertThat(result.playlistAdded).isEqualTo(2)
        assertThat(result.playlistCreated).isTrue()
    }

    @Test
    fun `the same file twice in one export is copied once`() = runTest {
        val bytes = ByteArray(3000) { 5 }
        val first = sources.add("a.aiff", bytes = bytes)
        val second = sources.add("a.aiff", bytes = bytes, uri = "content://elsewhere/a")

        val result = export(first, second) as ExportResult.Completed

        assertThat(result.exported).hasSize(1)
        assertThat(result.duplicates).hasSize(1)
    }

    @Test
    fun `an existing playlist name gets the new tracks and a blank name makes no playlist`() = runTest {
        export(sources.add("a.aiff"), playlist = "Set")
        export(sources.add("b.aiff"), playlist = "Other")
        val result = export(sources.add("c.aiff"), playlist = "set") as ExportResult.Completed
        export(sources.add("d.aiff"), playlist = "  ")

        assertThat(result.playlist).isEqualTo("Set")
        assertThat(result.playlistAdded).isEqualTo(1)
        assertThat(result.playlistCreated).isFalse()
        assertThat(manifest().playlists.map { it.name to it.trackIds })
            .containsExactly("Set" to listOf(1, 3), "Other" to listOf(2)).inOrder()
    }

    @Test
    fun `tracks already in the playlist are not added again and leave the library alone`() = runTest {
        val bytes = ByteArray(2000) { 4 }
        export(sources.add("a.aiff", bytes = bytes), playlist = "Set")
        stick.log.clear()

        val result = export(sources.add("again.aiff", bytes = bytes, uri = "content://x/again"), playlist = "Set")
            as ExportResult.Completed

        assertThat(result.libraryWritten).isFalse()
        assertThat(result.playlist).isNull()
        assertThat(stick.log).isEmpty()
        assertThat(manifest().playlists.single().trackIds).containsExactly(1)
    }

    @Test
    fun `when nothing new is added the library is left alone`() = runTest {
        val bytes = ByteArray(2000) { 3 }
        export(sources.add("a.aiff", bytes = bytes))
        stick.log.clear()

        val result = export(sources.add("again.aiff", bytes = bytes, uri = "content://x/again")) as ExportResult.Completed

        assertThat(result.libraryWritten).isFalse()
        assertThat(result.duplicates).hasSize(1)
        assertThat(stick.log).isEmpty()
    }

    @Test
    fun `a library rekordbox wrote is only replaced once the user agrees`() = runTest {
        stick.put(PDB_PATH, byteArrayOf(1, 2, 3))
        assertThat(exporter.readLibrary(stick)).isEqualTo(StickLibrary.Foreign)
        val track = sources.add("a.aiff")

        val refused = export(track)

        assertThat(refused).isEqualTo(ExportResult.NeedsConfirmation(StickLibrary.Foreign))
        assertThat(stick.paths).containsExactly(PDB_PATH)

        val replaced = export(track, replace = true) as ExportResult.Completed

        assertThat(replaced.exported).hasSize(1)
        assertThat(manifest().tracks).hasSize(1)
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `an unreadable manifest also needs the user's consent`() = runTest {
        stick.put(StickManifest.PATH, "{not json".toByteArray())

        val library = exporter.readLibrary(stick)

        assertThat(library).isInstanceOf(StickLibrary.Unreadable::class.java)
        assertThat(export(sources.add("a.aiff"))).isInstanceOf(ExportResult.NeedsConfirmation::class.java)
        assertThat(export(sources.add("a.aiff"), replace = true)).isInstanceOf(ExportResult.Completed::class.java)
    }

    @Test
    fun `an empty stick and a Sonora stick are told apart`() = runTest {
        assertThat(exporter.readLibrary(stick)).isEqualTo(StickLibrary.Empty)

        export(sources.add("a.aiff"))

        assertThat(exporter.readLibrary(stick)).isEqualTo(StickLibrary.Sonora(manifest()))
    }

    @Test
    fun `cancelling keeps the previous library and removes this export's files`() = runTest {
        export(sources.add("a.aiff"))
        val before = stick.snapshot()
        val b = sources.add("b.aiff")
        val c = sources.add("c.aiff")
        lateinit var job: kotlinx.coroutines.Job
        stick.onOpenWrite = { path -> if (path.endsWith("c.aiff")) job.cancel() }

        var result: ExportResult? = null
        job = launch { result = exporter.export(stick, ExportRequest(listOf(b, c), "Set")) }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(result).isNull()
        assertThat(stick.snapshot()).isEqualTo(before)
    }

    @Test
    fun `a track that cannot be read or analysed is reported and the others are exported`() = runTest {
        val good = sources.add("good.aiff")
        val beatless = sources.add("beatless.aiff")
        analyzer.failures[beatless.uri] = FakeAnalyzer.NO_BEATS
        val vanishing = sources.add("vanishing.aiff", failReadAfter = 100)

        val result = export(good, beatless, vanishing) as ExportResult.Completed

        assertThat(result.exported.map { it.name }).containsExactly("good.aiff")
        assertThat(result.failed.map { it.name to it.reason }).containsExactly(
            "beatless.aiff" to TrackFailure.NOT_ANALYSABLE,
            "vanishing.aiff" to TrackFailure.UNREADABLE,
        ).inOrder()
        assertThat(stick.paths.filter { it.startsWith("Contents/") }).containsExactly("Contents/UnknownArtist/UnknownAlbum/good.aiff")
        assertThat(stick.paths.count { it.startsWith("PIONEER/USBANLZ/") }).isEqualTo(2)
        assertThat(manifest().tracks.map { it.title }).containsExactly("good")
    }

    @Test
    fun `audio analysed for one stick is not analysed again for another, whatever its file is called`() = runTest {
        val bytes = "same audio".toByteArray() + ByteArray(5000) { (it * 7).toByte() }
        val first = sources.add("01 Track.aiff", bytes = bytes, tags = daftPunk)
        export(first)
        val renamed = sources.add("Renamed.aiff", bytes = bytes, uri = "content://elsewhere/Renamed.aiff")
        val otherStick = FakeStickFileSystem()

        val result = exporter.export(otherStick, ExportRequest(listOf(renamed), null)) as ExportResult.Completed

        assertThat(analyzer.analysed).containsExactly(first.uri)
        assertThat(result.exported.map { it.name }).containsExactly("Renamed.aiff")
        val track = ManifestJson.decode(otherStick.bytes(StickManifest.PATH)!!).tracks.single()
        assertThat(track.bpm).isEqualTo(128.0)
        assertThat(track.key).isEqualTo(MusicalKey.A_MINOR)
        assertThat(track.analyzerVersion).isEqualTo(FakeAnalyzer.ANALYSIS.analyzerVersion)
        val anlz = AnlzPaths.forAudioPath(track.filePath)
        val cached = AnlzTrack(track.filePath, FakeAnalyzer.ANALYSIS.beatGrid, FakeAnalyzer.ANALYSIS.waveform)
        assertThat(otherStick.bytes(anlz.datPath)).isEqualTo(AnlzWriter.writeDat(cached))
        assertThat(otherStick.bytes(anlz.extPath)).isEqualTo(AnlzWriter.writeExt(cached))
    }

    @Test
    fun `changed audio under the same name is analysed again`() = runTest {
        export(sources.add("Track.aiff", bytes = ByteArray(3000) { 1 }))
        val edited = sources.add("Track.aiff", bytes = ByteArray(3000) { 2 })

        exporter.export(FakeStickFileSystem(), ExportRequest(listOf(edited), null))

        assertThat(analyzer.analysed).hasSize(2)
    }

    @Test
    fun `a track whose file changes during the export is left out and its cached analysis dropped`() = runTest {
        val before = ByteArray(3000) { 1 }
        val after = ByteArray(3000) { 2 }
        val live = sources.add("Live.aiff", bytes = before, changedBytes = after)
        val good = sources.add("good.aiff")

        val result = export(live, good) as ExportResult.Completed

        assertThat(result.failed.map { it.name to it.reason })
            .containsExactly("Live.aiff" to TrackFailure.CHANGED_DURING_EXPORT)
        assertThat(result.exported.map { it.name }).containsExactly("good.aiff")
        assertThat(stick.paths.filter { it.startsWith("Contents/") })
            .containsExactly("Contents/UnknownArtist/UnknownAlbum/good.aiff")
        assertThat(stick.paths.count { it.startsWith("PIONEER/USBANLZ/") }).isEqualTo(2)
        assertThat(manifest().tracks.map { it.title }).containsExactly("good")
        // Analysed from bytes that may be neither version: nothing is kept under the hash taken first.
        assertThat(cache.entries.keys.map { it.first }).doesNotContain(sha256(before))
        assertThat(cache.entries.keys.map { it.first }).doesNotContain(sha256(after))
    }

    @Test
    fun `a cached analysis of a file that changes during the export is dropped too`() = runTest {
        val before = ByteArray(3000) { 1 }
        cache.entries[sha256(before) to FakeAnalyzer.ANALYSIS.analyzerVersion] = FakeAnalyzer.ANALYSIS
        val live = sources.add("Live.aiff", bytes = before, changedBytes = ByteArray(3000) { 2 })

        val result = export(live) as ExportResult.Completed

        assertThat(result.failed.single().reason).isEqualTo(TrackFailure.CHANGED_DURING_EXPORT)
        assertThat(analyzer.analysed).isEmpty()
        assertThat(cache.entries).isEmpty()
        assertThat(stick.paths).isEmpty()
    }

    @Test
    fun `a broken analysis cache never fails the export`() = runTest {
        cache.broken = true

        val result = export(sources.add("a.aiff"), sources.add("b.aiff")) as ExportResult.Completed

        assertThat(result.exported.map { it.name }).containsExactly("a.aiff", "b.aiff").inOrder()
        assertThat(result.failed).isEmpty()
        assertThat(analyzer.analysed).hasSize(2)
    }

    @Test
    fun `when every track fails the stick is left as it was`() = runTest {
        val beatless = sources.add("beatless.aiff")
        analyzer.failures[beatless.uri] = FakeAnalyzer.NO_BEATS

        val result = export(beatless, playlist = "Set") as ExportResult.Completed

        assertThat(result.libraryWritten).isFalse()
        assertThat(stick.paths).isEmpty()
    }

    @Test
    fun `a failing stick stops the export and keeps the previous library`() = runTest {
        export(sources.add("a.aiff"))
        val before = stick.snapshot()
        stick.failWrites = { it.endsWith("c.aiff") }

        val result = export(sources.add("b.aiff"), sources.add("c.aiff"))

        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
        assertThat((result as ExportResult.Failed).libraryChanged).isFalse()
        assertThat(stick.snapshot()).isEqualTo(before)
    }

    @Test
    fun `a stick without room for the export fails before anything is written`() = runTest {
        stick.free = 1_000_000
        val big = sources.add("big.aiff", bytes = ByteArray(2_000_000))

        val result = export(big)

        assertThat(result).isInstanceOf(ExportResult.NotEnoughSpace::class.java)
        assertThat((result as ExportResult.NotEnoughSpace).freeBytes).isEqualTo(1_000_000)
        assertThat(result.neededBytes).isGreaterThan(2_000_000)
        assertThat(stick.paths).isEmpty()
    }

    @Test
    fun `the needed room covers the audio, the analysis files and the library`() {
        assertThat(StickExporter.requiredBytes(newTrackBytes = 0, trackCount = 0, libraryTrackCount = 0)).isGreaterThan(0)
        val ten = StickExporter.requiredBytes(newTrackBytes = 100_000_000, trackCount = 10, libraryTrackCount = 0)
        assertThat(ten).isGreaterThan(100_000_000 + 10 * 100_000L)
    }

    @Test
    fun `progress goes through each track and ends with the library`() = runTest {
        val progress = mutableListOf<ExportProgress>()

        exporter.export(stick, ExportRequest(listOf(sources.add("a.aiff"), sources.add("b.aiff")), null)) { progress += it }

        assertThat(progress.map { it.trackIndex to it.phase }.distinct()).containsExactly(
            0 to ExportPhase.PREPARING, 0 to ExportPhase.ANALYSING, 0 to ExportPhase.COPYING,
            1 to ExportPhase.PREPARING, 1 to ExportPhase.ANALYSING, 1 to ExportPhase.COPYING,
            2 to ExportPhase.WRITING_LIBRARY,
        ).inOrder()
        assertThat(progress.map { it.overallFraction }).isInOrder()
        assertThat(progress.last().overallFraction).isEqualTo(1f)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a track the player cannot take is converted, exported from the converted file and the temporary file deleted`() = runTest {
        val song = sources.add("Song.flac", audio = flac, tags = daftPunk)
        val progress = mutableListOf<ExportProgress>()

        val result = exporter.export(stick, ExportRequest(listOf(song), null)) { progress += it } as ExportResult.Completed

        assertThat(converter.converted).containsExactly(song.uri)
        val track = manifest().tracks.single()
        assertThat(track.filePath).isEqualTo("/Contents/Daft Punk/Discovery/Song.aiff")
        assertThat(track.title).isEqualTo("One More Time") // tags from the original
        assertThat(track.bitDepth).isEqualTo(16)
        assertThat(track.sampleRate).isEqualTo(44_100)
        val convertedUri = sources.files.keys.single { it.startsWith("file:") }
        assertThat(stick.bytes("Contents/Daft Punk/Discovery/Song.aiff")).isEqualTo(sources.files.getValue(convertedUri).bytes)
        assertThat(analyzer.analysed).containsExactly(convertedUri) // the analysis is of what goes on the stick
        assertThat(result.exported).containsExactly(ExportedTrack("Song.flac", "/Contents/Daft Punk/Discovery/Song.aiff"))
        assertThat(converter.open).isEmpty()
        // Converting comes first and fills the start of the track's progress.
        assertThat(progress.first().phase).isEqualTo(ExportPhase.CONVERTING)
        val phases = progress.map { it.phase }.distinct()
        assertThat(phases).containsAtLeast(ExportPhase.CONVERTING, ExportPhase.ANALYSING, ExportPhase.COPYING).inOrder()
        assertThat(progress.map { it.overallFraction }).isInOrder()
    }

    @Test
    fun `tracks that fit are never converted and leftovers of an earlier run are discarded first`() = runTest {
        export(sources.add("a.aiff"))

        assertThat(converter.converted).isEmpty()
        assertThat(converter.leftoversDiscarded).isEqualTo(1)
    }

    @Test
    fun `the same file converted again is found on the stick and not copied twice`() = runTest {
        val song = sources.add("Song.flac", audio = flac)
        export(song)

        val again = export(song, playlist = "Set") as ExportResult.Completed

        assertThat(again.exported).isEmpty()
        assertThat(again.duplicates.map { it.name }).containsExactly("Song.flac")
        assertThat(manifest().tracks).hasSize(1)
        assertThat(converter.open).isEmpty()
    }

    @Test
    fun `a file that cannot be decoded is reported and the others are exported`() = runTest {
        val broken = sources.add("broken.ogg", audio = SourceAudio(AudioContainer.OGG))
        converter.failures[broken.uri] = io.sonora.core.domain.audio.PcmDecodeException.UnsupportedFormat("vorbis?")
        val good = sources.add("good.aiff")

        val result = export(broken, good) as ExportResult.Completed

        assertThat(result.failed.map { it.name to it.reason }).containsExactly("broken.ogg" to TrackFailure.NOT_CONVERTIBLE)
        assertThat(result.exported.map { it.name }).containsExactly("good.aiff")
        assertThat(stick.paths.none { "broken" in it }).isTrue()
        assertThat(converter.open).isEmpty()
    }

    @Test
    fun `a file the player cannot take and the decoder cannot read is not converted at all`() = runTest {
        val unknown = sources.add("notes.aiff", audio = SourceAudio(AudioContainer.AIFF_C, compression = "ima4"))

        val result = export(unknown) as ExportResult.Completed

        assertThat(converter.converted).isEmpty()
        assertThat(result.failed.single().reason).isEqualTo(TrackFailure.NOT_CONVERTIBLE)
    }

    @Test
    fun `the temporary file is deleted when the converted track fails later`() = runTest {
        val song = sources.add("Song.flac", audio = flac)
        converter.onConvert = { analyzer.failures["file:///cache/export-conversions/0.aiff"] = FakeAnalyzer.NO_BEATS }

        val result = export(song) as ExportResult.Completed

        assertThat(result.failed.single().reason).isEqualTo(TrackFailure.NOT_ANALYSABLE)
        assertThat(converter.open).isEmpty()
        assertThat(stick.paths.none { it.startsWith("Contents/") }).isTrue()
    }

    @Test
    fun `cancelling while a converted track is copied deletes the temporary file`() = runTest {
        val song = sources.add("Song.flac", audio = flac)
        val job = launch {
            stick.onOpenWrite = { if (it.endsWith("Song.aiff")) coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
            exporter.export(stick, ExportRequest(listOf(song), null))
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(converter.open).isEmpty()
        assertThat(stick.paths).isEmpty()
    }

    @Test
    fun `the room an export needs counts converted tracks at their converted size`() = runTest {
        // 10 s at 44.1 kHz stereo 16-bit is about 1.8 MB, far more than this tiny FLAC.
        val song = sources.add("Song.flac", audio = flac)
        stick.free = StickExporter.requiredBytes(song.sizeBytes, 1, 0) + 1_000

        val result = export(song)

        assertThat(result).isInstanceOf(ExportResult.NotEnoughSpace::class.java)
        assertThat(converter.converted).isEmpty()
    }
}
