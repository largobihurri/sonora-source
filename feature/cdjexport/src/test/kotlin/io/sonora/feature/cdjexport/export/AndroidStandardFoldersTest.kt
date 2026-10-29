package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.StickEntry
import io.sonora.core.domain.export.StickIoException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidStandardFoldersTest {

    private val stick = FakeStickFileSystem()

    @Test
    fun `Android's empty standard folders at the root are removed, empty subfolders with them`() {
        AndroidStandardFolders.NAMES.forEach { stick.emptyFolders += it }
        stick.emptyFolders += "DCIM/.thumbnails"
        stick.emptyFolders += "Music/Sub/Deeper"

        val removed = AndroidStandardFolders.removeEmpty(stick)

        assertThat(removed).containsExactlyElementsIn(AndroidStandardFolders.NAMES)
        assertThat(stick.list("")).isEmpty()
    }

    @Test
    fun `the list is exactly Android's standard folders`() {
        assertThat(AndroidStandardFolders.NAMES).containsExactly(
            "Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music", "Notifications", "Pictures",
            "Podcasts", "Recordings", "Ringtones", "LOST.DIR", ".trash-storage",
        )
    }

    @Test
    fun `a standard folder holding a file at any depth is kept whole`() {
        stick.put("Music/Set/a.mp3", byteArrayOf(1))
        stick.emptyFolders += "Music/Empty"
        stick.put("DCIM/photo.jpg", byteArrayOf(2))
        stick.emptyFolders += "Movies"

        assertThat(AndroidStandardFolders.removeEmpty(stick)).containsExactly("Movies")
        assertThat(stick.exists("Music/Set/a.mp3")).isTrue()
        assertThat(stick.list("Music")!!.map { it.name }).containsExactly("Set", "Empty")
        assertThat(stick.exists("DCIM/photo.jpg")).isTrue()
    }

    @Test
    fun `nothing else is ever removed, however empty`() {
        listOf("Contents", "PIONEER", "music", "Musica", "Downloads", "My Music").forEach { stick.emptyFolders += it }
        stick.put("Alarms", byteArrayOf(1)) // a file, not a folder

        assertThat(AndroidStandardFolders.removeEmpty(stick)).isEmpty()
        assertThat(stick.list("")!!.map { it.name }).containsExactly(
            "Contents", "PIONEER", "music", "Musica", "Downloads", "My Music", "Alarms",
        )
    }

    @Test
    fun `the folders Android filled with only its marker files go, the stick's own data stays`() {
        // Seen on a stick after a Sonora format and export, once Android had mounted it (2026-10-04).
        listOf("Movies", "Music", "Pictures").forEach { stick.put("$it/.thumbnails/.database_uuid", ByteArray(36)) }
        stick.emptyFolders += "Android/data/com.oplus.ndsf/cache"
        stick.emptyFolders += "Android/data/com.google.android.apps.nbu.files/files"
        stick.put("Android/media/.nomedia", ByteArray(0))
        stick.put("PIONEER/rekordbox/export.pdb", byteArrayOf(1))
        stick.put("Contents/Artist/a.aiff", byteArrayOf(2))
        stick.emptyFolders += "PIONEER/Empty"

        val removed = AndroidStandardFolders.removeEmpty(stick)

        assertThat(removed).containsExactly("Movies", "Music", "Pictures", "Android")
        assertThat(stick.list("")!!.map { it.name }).containsExactly("PIONEER", "Contents")
        assertThat(stick.exists("PIONEER/rekordbox/export.pdb")).isTrue()
        assertThat(stick.exists("Contents/Artist/a.aiff")).isTrue()
    }

    @Test
    fun `a marker file does not hide music, photos or app files next to it`() {
        stick.put("Music/.thumbnails/.database_uuid", ByteArray(36))
        stick.put("Music/Set/a.mp3", byteArrayOf(1))
        stick.put("Pictures/.thumbnails/.database_uuid", ByteArray(36))
        stick.put("Pictures/photo.jpg", byteArrayOf(2))
        stick.emptyFolders += "Android/data/com.oplus.ndsf/cache"
        stick.put("Android/data/com.example/files/save.dat", byteArrayOf(3))

        assertThat(AndroidStandardFolders.removeEmpty(stick)).isEmpty()
        assertThat(stick.exists("Music/Set/a.mp3")).isTrue()
        assertThat(stick.exists("Music/.thumbnails/.database_uuid")).isTrue()
        assertThat(stick.exists("Pictures/photo.jpg")).isTrue()
        assertThat(stick.exists("Android/data/com.example/files/save.dat")).isTrue()
    }

    @Test
    fun `only small files with exactly the marker names count as markers`() {
        stick.put("Movies/.thumbnails/.database_uuid", ByteArray(65))
        stick.put("Music/.nomedia", ByteArray(65))
        stick.put("Pictures/.thumbnails/.database_uuid.bak", ByteArray(1))
        stick.put("DCIM/.thumbnails/.DATABASE_UUID", ByteArray(1))
        stick.put("Alarms/.nomedia", ByteArray(64))

        assertThat(AndroidStandardFolders.removeEmpty(stick)).containsExactly("Alarms")
        assertThat(stick.list("")!!.map { it.name }).containsExactly("Movies", "Music", "Pictures", "DCIM")
    }

    @Test
    fun `a marker whose size cannot be read is kept as a file`() {
        stick.put("Music/.thumbnails/.database_uuid", ByteArray(36))
        val sizeless = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun sizeOf(path: String): Long? = throw StickIoException("gone")
        }

        assertThat(AndroidStandardFolders.removeEmpty(sizeless)).isEmpty()
        assertThat(stick.exists("Music/.thumbnails/.database_uuid")).isTrue()
    }

    @Test
    fun `a folder that cannot be listed is kept`() {
        stick.emptyFolders += "Pictures"
        val unlistable = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun list(path: String): List<StickEntry>? =
                if (path == "Pictures") throw StickIoException("gone") else stick.list(path)
        }

        assertThat(AndroidStandardFolders.removeEmpty(unlistable)).isEmpty()
        assertThat(stick.list("")!!.map { it.name }).containsExactly("Pictures")
    }

    @Test
    fun `an export clears them and a stick that cannot be listed still exports`() = runTest {
        val sources = FakeSources()
        val exporter = StickExporter(sources, FakeAnalyzer().cached(), java.time.Clock.systemUTC(), FakeConverter(sources))
        stick.emptyFolders += "Alarms"
        stick.emptyFolders += "LOST.DIR"
        stick.put("Download/keep.txt", byteArrayOf(1))

        val result = exporter.export(stick, ExportRequest(listOf(sources.add("a.aiff")), null))

        assertThat(result).isInstanceOf(ExportResult.Completed::class.java)
        assertThat(stick.list("")!!.map { it.name }).containsNoneOf("Alarms", "LOST.DIR")
        assertThat(stick.exists("Download/keep.txt")).isTrue()

        val failing = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun list(path: String): List<StickEntry>? = throw StickIoException("cannot list")
        }
        val again = exporter.export(failing, ExportRequest(listOf(sources.add("b.aiff")), null))

        assertThat((again as ExportResult.Completed).exported).hasSize(1)
    }

    @Test
    fun `a stick failing in an unexpected way keeps its folders and the export goes on`() = runTest {
        stick.emptyFolders += "Alarms"
        stick.emptyFolders += "Music"
        val odd = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun list(path: String): List<StickEntry>? =
                if (path == "Alarms") throw SecurityException("access revoked") else stick.list(path)

            override fun delete(path: String): Boolean =
                if (path == "Music") throw IllegalStateException("provider gone") else stick.delete(path)
        }

        assertThat(AndroidStandardFolders.removeEmpty(odd)).isEmpty()
        assertThat(stick.list("")!!.map { it.name }).containsExactly("Alarms", "Music")

        val rootless = object : io.sonora.core.domain.export.StickFileSystem by stick {
            override fun list(path: String): List<StickEntry>? =
                if (path.isEmpty()) throw IllegalArgumentException("bad tree") else stick.list(path)
        }
        val sources = FakeSources()
        val converter = object : ExportTrackConverter by FakeConverter(sources) {
            override fun discardLeftovers() = throw SecurityException("cache gone")
        }
        val exporter = StickExporter(sources, FakeAnalyzer().cached(), java.time.Clock.systemUTC(), converter)

        val result = exporter.export(rootless, ExportRequest(listOf(sources.add("a.aiff")), null))

        assertThat((result as ExportResult.Completed).exported).hasSize(1)
    }
}

