package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.reference.PdbReader
import java.time.LocalDate
import org.junit.Test

/** Tables that spill over many pages: every row must land on some page, none may be dropped or overflow its page. */
class PdbMultiPageTest {

    @Test
    fun `300 tracks keep every row across chained pages`() {
        checkLibrary(SyntheticLibrary.build(300))
    }

    @Test
    fun `1000 tracks with long and non-ascii strings keep every row across chained pages`() {
        checkLibrary(SyntheticLibrary.build(1000))
    }

    @Test
    fun `extra pages are appended after the template pages and empty candidates lie past the end`() {
        val pdb = PdbReader.read(PdbWriter.write(SyntheticLibrary.build(1000)))
        val tracks = pdb.chain(PdbTable.TRACKS)

        assertThat(tracks[1].index).isEqualTo(PdbTable.TRACKS.firstDataPage)
        assertThat(tracks.drop(2).map { it.index }).isEqualTo((41 until 41 + tracks.size - 2).toList())
        pdb.tables.forEach { table ->
            val empty = table.firstPage == table.lastPage
            if (!empty) assertThat(table.emptyCandidate).isAtLeast(pdb.pageCount)
        }
        assertThat(pdb.nextUnusedPage).isEqualTo(pdb.tables.maxOf { it.emptyCandidate } + 1)
    }

    private fun checkLibrary(library: DeviceLibrary) {
        val pdb = PdbReader.read(PdbWriter.write(library))
        PdbReader.checkStructure(pdb)

        val tracks = pdb.rows(PdbTable.TRACKS)
        assertThat(tracks.map { it.u32(0x48).toInt() }).containsExactlyElementsIn(library.tracks.map { it.id }).inOrder()
        assertThat(tracks.map { it.string(it.u16(0x5E + 2 * 17)) })
            .containsExactlyElementsIn(library.tracks.map { it.title }).inOrder()
        assertThat(tracks.map { it.string(it.u16(0x5E + 2 * 20)) })
            .containsExactlyElementsIn(library.tracks.map { it.filePath }).inOrder()
        assertThat(pdb.dataPages(PdbTable.TRACKS).size).isGreaterThan(1)

        val rows = PdbWriter.rowsOf(library)
        for (table in rows.keys) assertThat(pdb.rows(table).size).isEqualTo(rows.getValue(table).size)
        val entries = pdb.rows(PdbTable.PLAYLIST_ENTRIES).map { Triple(it.u32(0).toInt(), it.u32(4).toInt(), it.u32(8).toInt()) }
        val expected = library.playlists.flatMapIndexed { p, playlist ->
            playlist.trackIds.mapIndexed { i, id -> Triple(i + 1, id, p + 1) }
        }
        assertThat(entries).containsExactlyElementsIn(expected).inOrder()
        assertThat(pdb.rows(PdbTable.ARTISTS).map { it.string(it.u8(9)) })
            .containsExactlyElementsIn(library.tracks.mapNotNull { it.artist }.distinct()).inOrder()
    }
}

/** Libraries of [build] `n` AIFF tracks with long, non-ascii and mixed strings, and three playlists over them. */
object SyntheticLibrary {
    private val words = listOf("Nacht", "Señal", "Déjà vu", "東京", "Ψυχή", "Rave", "Groove", "Ünter", "Kraków", "Øresund")

    fun build(n: Int): DeviceLibrary {
        val tracks = (1..n).map { id ->
            val artist = if (id % 7 == 0) "Ärtist ${id % 50}" else "Artist ${id % 50}"
            val album = "Album ${id % 120} — ${words[id % words.size]}"
            val title = buildString {
                append(words[id % words.size]).append(' ').append(id)
                if (id % 3 == 0) append(" (Extended Mix) ").append("x".repeat(80))
            }
            val filePath = "/Contents/$artist/$album/${"%04d".format(id)} $title.aiff"
            DeviceTrack(
                id = id,
                title = title,
                artist = artist,
                album = album,
                tempoBpm = 120.0 + id % 30,
                durationSeconds = 180 + id % 240,
                sampleRate = if (id % 2 == 0) 44_100 else 48_000,
                bitDepth = 16,
                bitrateKbps = if (id % 2 == 0) 1411 else 1536,
                fileSize = 30_000_000L + id,
                filePath = filePath,
                analyzePath = "/PIONEER/USBANLZ/P%03X/%08X/ANLZ0000.DAT".format(id % 4096, id),
                dateAdded = LocalDate.of(2026, 1, 1).plusDays(id % 365L),
                genre = if (id % 5 == 0) null else "Genre ${id % 17}",
                label = if (id % 4 == 0) "Label ${id % 23}" else null,
                key = MusicalKey.fromIndex(id % 25),
                trackNumber = id % 12 + 1,
                year = 1990 + id % 35,
                comment = if (id % 2 == 0) "Comment ${"ç".repeat(id % 60)}" else "A".repeat(id % 200),
            )
        }
        val ids = tracks.map { it.id }
        val playlists = listOf(
            DevicePlaylist("All tracks", ids),
            DevicePlaylist("Évens", ids.filter { it % 2 == 0 }.reversed()),
            DevicePlaylist("Short", ids.take(5)),
        )
        return DeviceLibrary(tracks, playlists, exportDate = LocalDate.of(2026, 10, 3))
    }
}
