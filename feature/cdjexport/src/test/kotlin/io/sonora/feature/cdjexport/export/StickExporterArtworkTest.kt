package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.rekordbox.manifest.ManifestJson
import io.sonora.core.rekordbox.manifest.StickManifest
import io.sonora.core.rekordbox.pdb.ArtworkRow
import io.sonora.core.rekordbox.pdb.PdbTable
import io.sonora.core.rekordbox.pdb.PdbWriter
import io.sonora.core.rekordbox.pdb.TrackRow
import io.sonora.feature.cdjexport.export.StickExporter.Companion.PDB_PATH
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Covers and comments on the stick: the rekordbox artwork layout, pictures shared by hash, re-exports filling them in. */
class StickExporterArtworkTest {

    private val today = LocalDate.of(2026, 10, 4)
    private val clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val artwork = FakeArtwork()
    private val stick = FakeStickFileSystem()
    private val exporter = StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources), artwork)

    private val front = byteArrayOf(1, 2, 3)
    private val other = byteArrayOf(4, 5, 6)

    private fun cover(bytes: ByteArray) = CoverArt(bytes, "image/jpeg", 600, 600)

    private val dubWarrior = SourceTags(
        title = "Dub Warrior", artist = "Aryeh Yah", album = "Jah Warrior", genre = "Dub",
        comment = "Visit\nhttps://example.bandcamp.com", cover = cover(front),
    )

    private suspend fun export(vararg tracks: SourceFile, using: StickExporter = exporter) =
        using.export(stick, ExportRequest(tracks.toList(), "Set")) as ExportResult.Completed

    private fun manifest(): StickManifest = ManifestJson.decode(stick.bytes(StickManifest.PATH)!!)

    @Test
    fun `a track's cover goes to the rekordbox artwork folder in both sizes and its comment to the library`() = runTest {
        export(sources.add("02 Dub Warrior.aiff", tags = dubWarrior))

        assertThat(stick.bytes("PIONEER/Artwork/00001/a1.jpg")).isEqualTo("S".toByteArray() + front)
        assertThat(stick.bytes("PIONEER/Artwork/00001/a1_m.jpg")).isEqualTo("L".toByteArray() + front)
        val track = manifest().tracks.single()
        assertThat(track.artworkId).isEqualTo(1)
        assertThat(track.comment).isEqualTo("Visit https://example.bandcamp.com")
        assertThat(track.genre).isEqualTo("Dub")
        assertThat(manifest().artwork.single().id).isEqualTo(1)
        assertThat(manifest().artwork.single().sha256).hasLength(64)

        val rows = PdbWriter.rowsOf(manifest().toDeviceLibrary(today))
        assertThat(rows.getValue(PdbTable.ARTWORK)).containsExactly(ArtworkRow(1, "/PIONEER/Artwork/00001/a1.jpg"))
        val row = rows.getValue(PdbTable.TRACKS).single() as TrackRow
        assertThat(row.artworkId).isEqualTo(1)
        assertThat(row.comment).isEqualTo("Visit https://example.bandcamp.com")
        assertThat(row.genreId).isEqualTo(1)
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `tracks with the same cover share one picture, other covers get the next ids`() = runTest {
        val one = sources.add("one.aiff", tags = SourceTags(title = "One", cover = cover(front)))
        val two = sources.add("two.aiff", tags = SourceTags(title = "Two", cover = cover(front.copyOf())))
        val three = sources.add("three.aiff", tags = SourceTags(title = "Three", cover = cover(other)))

        export(one, two, three)

        assertThat(manifest().tracks.map { it.artworkId }).containsExactly(1, 1, 2).inOrder()
        assertThat(manifest().artwork.map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(stick.paths.filter { it.startsWith("PIONEER/Artwork") }).containsExactly(
            "PIONEER/Artwork/00001/a1.jpg", "PIONEER/Artwork/00001/a1_m.jpg",
            "PIONEER/Artwork/00001/a2.jpg", "PIONEER/Artwork/00001/a2_m.jpg",
        )
    }

    @Test
    fun `a later export reuses a picture already on the stick`() = runTest {
        export(sources.add("one.aiff", tags = SourceTags(title = "One", cover = cover(front))))
        stick.log.clear()

        export(sources.add("two.aiff", tags = SourceTags(title = "Two", cover = cover(front))))

        assertThat(manifest().tracks.map { it.artworkId }).containsExactly(1, 1)
        assertThat(stick.log.filter { "Artwork" in it }).isEmpty()
    }

    @Test
    fun `without an embedded cover the library's folder image is used, without either there is none`() = runTest {
        val folderArt = sources.add("folder.aiff")
        artwork.libraryPictures[folderArt.uri] = other
        val bare = sources.add("bare.aiff")

        export(folderArt, bare)

        assertThat(manifest().tracks.map { it.artworkId }).containsExactly(1, null).inOrder()
        assertThat(stick.bytes("PIONEER/Artwork/00001/a1_m.jpg")).isEqualTo("L".toByteArray() + other)
        assertThat(manifest().tracks.map { it.extrasRead }).containsExactly(true, true)
    }

    @Test
    fun `a cover that does not decode leaves the track without one`() = runTest {
        artwork.undecodable += front.toList()

        val result = export(sources.add("song.aiff", tags = dubWarrior))

        assertThat(result.exported).hasSize(1)
        assertThat(manifest().tracks.single().artworkId).isNull()
        assertThat(manifest().artwork).isEmpty()
        assertThat(stick.paths.none { it.startsWith("PIONEER/Artwork") }).isTrue()
    }

    @Test
    fun `picture files the library does not know are never overwritten`() = runTest {
        stick.put("PIONEER/Artwork/00001/a1.jpg", byteArrayOf(9))

        export(sources.add("song.aiff", tags = dubWarrior))

        assertThat(stick.bytes("PIONEER/Artwork/00001/a1.jpg")).isEqualTo(byteArrayOf(9))
        assertThat(manifest().tracks.single().artworkId).isEqualTo(2)
        assertThat(stick.bytes("PIONEER/Artwork/00001/a2.jpg")).isEqualTo("S".toByteArray() + front)
    }

    @Test
    fun `a track a failed export leaves out leaves no picture behind`() = runTest {
        val song = sources.add("song.aiff", tags = dubWarrior, failReadAfter = 10)

        val result = export(song)

        assertThat(result.failed).hasSize(1)
        assertThat(stick.paths.none { it.startsWith("PIONEER/Artwork") }).isTrue()
    }

    @Test
    fun `tracks an older Sonora exported gain their cover, comment and genre when exported again, audio untouched`() = runTest {
        val song = sources.add("02 Dub Warrior.aiff", tags = dubWarrior)
        val noArtwork = StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources))
        export(song, using = noArtwork)
        // As a Sonora before covers and comments wrote it: no comment, no artwork, and here no genre either.
        val old = manifest().copy(tracks = manifest().tracks.map { it.copy(comment = null, artworkId = null, genre = null, extrasRead = false) })
        stick.put(StickManifest.PATH, ManifestJson.encode(old))
        assertThat(ManifestJson.encode(old).toString(Charsets.UTF_8)).doesNotContain("artworkId")
        stick.log.clear()

        val result = export(song)

        assertThat(result.duplicates).hasSize(1)
        assertThat(result.libraryWritten).isTrue()
        assertThat(stick.log.none { it.startsWith("write Contents/") }).isTrue()
        val track = manifest().tracks.single()
        assertThat(track.artworkId).isEqualTo(1)
        assertThat(track.comment).isEqualTo("Visit https://example.bandcamp.com")
        assertThat(track.genre).isEqualTo("Dub")
        assertThat(track.extrasRead).isTrue()
        assertThat(stick.exists("PIONEER/Artwork/00001/a1.jpg")).isTrue()
        assertThat(stick.bytes(PDB_PATH)).isEqualTo(PdbWriter.write(manifest().toDeviceLibrary(today)))
    }

    @Test
    fun `tracks whose cover and comment were already looked for are not read again`() = runTest {
        val song = sources.add("song.aiff", tags = SourceTags(title = "Plain"))
        export(song)
        sources.tagReads.clear()
        stick.log.clear()

        val result = export(song)

        assertThat(sources.tagReads).isEmpty()
        assertThat(result.libraryWritten).isFalse()
        assertThat(stick.log).isEmpty()
    }

    /** [song] on the stick as a Sonora before covers and comments listed it. */
    private suspend fun exportedByAnOlderSonora(song: SourceFile) {
        val noArtwork = StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources))
        export(song, using = noArtwork)
        val old = manifest().copy(tracks = manifest().tracks.map { it.copy(comment = null, artworkId = null, extrasRead = false) })
        stick.put(StickManifest.PATH, ManifestJson.encode(old))
    }

    @Test
    fun `a track on the stick whose tags cannot be read now is a duplicate only, and is looked at again next time`() = runTest {
        val song = sources.add("02 Dub Warrior.aiff", tags = dubWarrior)
        exportedByAnOlderSonora(song)
        sources.tagsUnreadable += song.uri

        val result = export(song)

        assertThat(result.duplicates).hasSize(1)
        assertThat(result.failed).isEmpty()
        assertThat(manifest().tracks.single().extrasRead).isFalse()
        assertThat(manifest().tracks.single().artworkId).isNull()

        sources.tagsUnreadable.clear()
        val again = export(song)

        assertThat(again.libraryWritten).isTrue()
        assertThat(manifest().tracks.single().extrasRead).isTrue()
        assertThat(manifest().tracks.single().artworkId).isEqualTo(1)
    }

    @Test
    fun `a new track whose tags cannot be read now is reported once, as unreadable`() = runTest {
        val song = sources.add("a.aiff", tags = dubWarrior)
        sources.tagsUnreadable += song.uri

        val result = export(song)

        assertThat(result.failed.single().reason).isEqualTo(TrackFailure.UNREADABLE)
        assertThat(result.exported).isEmpty()
        assertThat(result.duplicates).isEmpty()
        assertThat(stick.paths.none { it.startsWith("Contents/") }).isTrue()
    }
}
