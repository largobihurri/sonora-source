package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.domain.export.StickEntry
import io.sonora.core.domain.export.StickFileSystem
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.rekordbox.manifest.StickManifest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Exports two synthetic AIFF tracks in two runs (the second adds a playlist) to a real directory, `build/stick-out`
 * (system property `sonora.stickOutDir`), so the whole stick tree can be checked with rekordcrate:
 * `rekordcrate dump-pdb build/stick-out/PIONEER/rekordbox/export.pdb` and `rekordcrate dump-anlz <.DAT/.EXT>`.
 */
class StickTreeOracleTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `a complete stick tree is written to the output directory`() = runTest {
        val root = File(System.getProperty("sonora.stickOutDir") ?: return@runTest)
        root.deleteRecursively()
        root.mkdirs()
        val stick = DirectoryStick(root)
        val sources = FakeSources()
        val exporter = StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources))
        val audio = SourceAudio(AudioContainer.AIFF, bitsPerSample = 16, sampleRateHz = 44_100, channelCount = 2)
        val first = sources.add(
            "01 Click 128.aiff",
            bytes = aiff(seconds = 10, toneHz = 440.0),
            tags = SourceTags(title = "Click 128", artist = "Sonora Test", album = "Oracle", trackNumber = 1, year = 2026),
            audio = audio,
        )
        val second = sources.add(
            "02 Ünïcödé Tone.aiff",
            bytes = aiff(seconds = 10, toneHz = 220.0),
            tags = SourceTags(title = "Ünïcödé Tone", artist = "Sonora Test", album = "Oracle", genre = "Test"),
            audio = audio,
        )

        exporter.export(stick, ExportRequest(listOf(first), playlistName = null))
        val result = exporter.export(stick, ExportRequest(listOf(first, second), playlistName = "Oracle Set"))

        assertThat((result as ExportResult.Completed).exported.map { it.name }).containsExactly("02 Ünïcödé Tone.aiff")
        assertThat(result.duplicates.map { it.name }).containsExactly("01 Click 128.aiff")
        listOf(StickExporter.PDB_PATH, StickExporter.EXT_PDB_PATH, StickExporter.DEVSETTING_PATH, StickManifest.PATH)
            .forEach { assertThat(File(root, it).isFile).isTrue() }
        assertThat(File(root, "Contents/Sonora Test/Oracle/01 Click 128.aiff").length()).isEqualTo(first.sizeBytes)
        assertThat(root.walk().count { it.name == "ANLZ0000.DAT" }).isEqualTo(2)
    }

    /** A 16-bit stereo 44.1 kHz AIFF with a sine tone. */
    private fun aiff(seconds: Int, toneHz: Double): ByteArray {
        val rate = 44_100
        val frames = rate * seconds
        val sound = ByteArrayOutputStream()
        DataOutputStream(sound).use { out ->
            for (i in 0 until frames) {
                val sample = (sin(2 * PI * toneHz * i / rate) * 8000).toInt()
                out.writeShort(sample)
                out.writeShort(sample)
            }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val comm = 18
            val ssnd = 8 + sound.size()
            out.writeBytes("FORM")
            out.writeInt(4 + 8 + comm + 8 + ssnd)
            out.writeBytes("AIFF")
            out.writeBytes("COMM")
            out.writeInt(comm)
            out.writeShort(2)
            out.writeInt(frames)
            out.writeShort(16)
            out.write(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)) // 44100 as 80-bit extended
            out.writeBytes("SSND")
            out.writeInt(ssnd)
            out.writeInt(0)
            out.writeInt(0)
            sound.writeTo(out)
        }
        return bytes.toByteArray()
    }

    /** A stick backed by a directory. */
    private class DirectoryStick(private val root: File) : StickFileSystem {
        override fun freeBytes(): Long = root.usableSpace

        override fun exists(path: String): Boolean = File(root, path).exists()

        override fun sizeOf(path: String): Long? = File(root, path).takeIf { it.isFile }?.length()

        override fun read(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()

        override fun openWrite(path: String): OutputStream {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            return file.outputStream()
        }

        override fun replace(source: String, target: String) {
            val from = File(root, source)
            val to = File(root, target)
            to.delete()
            if (!from.renameTo(to)) throw StickIoException("Cannot rename $source")
        }

        override fun delete(path: String): Boolean = File(root, path).let { it.exists() && it.deleteRecursively() }

        override fun list(path: String): List<StickEntry>? =
            File(root, path).takeIf { it.isDirectory }?.listFiles()?.map { StickEntry(it.name, it.isDirectory) }
    }
}
