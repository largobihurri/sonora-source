package io.sonora.feature.cdjexport.export

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.AnalysisCache
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.CachedTrackAnalyzer
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.domain.analysis.TrackAnalyzer
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import io.sonora.core.domain.port.TrackFileInfoReader
import io.sonora.core.rekordbox.manifest.ManifestJson
import io.sonora.core.rekordbox.manifest.StickManifest
import io.sonora.core.rekordbox.setting.DevSettingWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The SAF adapters against a real document provider ([TestDocumentsProvider]) through the content resolver. */
@RunWith(AndroidJUnit4::class)
class SafAdaptersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = TestDocumentsProvider.rootDirectory(context.cacheDir)
    private val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val stick = SafStickFileSystem(context.contentResolver, tree, TestDocumentsProvider.ROOT_ID) { 1L shl 30 }

    @Before
    fun setUp() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun filesAreWrittenReadReplacedAndDeletedThroughTheProvider() {
        stick.openWrite("PIONEER/rekordbox/export.pdb").use { it.write(byteArrayOf(1, 2, 3)) }

        assertThat(File(root, "PIONEER/rekordbox/export.pdb").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(stick.exists("pioneer/REKORDBOX/export.pdb")).isTrue()
        assertThat(stick.sizeOf("PIONEER/rekordbox/export.pdb")).isEqualTo(3)
        assertThat(stick.read("PIONEER/rekordbox/export.pdb")).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(stick.read("PIONEER/missing")).isNull()

        stick.openWrite("PIONEER/rekordbox/export.pdb.sonora-new").use { it.write(byteArrayOf(9)) }
        stick.replace("PIONEER/rekordbox/export.pdb.sonora-new", "PIONEER/rekordbox/export.pdb")

        assertThat(File(root, "PIONEER/rekordbox").list()!!.toList()).containsExactly("export.pdb")
        assertThat(stick.read("PIONEER/rekordbox/export.pdb")).isEqualTo(byteArrayOf(9))

        stick.openWrite("PIONEER/rekordbox/export.pdb").use { it.write(byteArrayOf(7, 7)) }
        assertThat(File(root, "PIONEER/rekordbox/export.pdb").readBytes()).isEqualTo(byteArrayOf(7, 7))

        assertThat(stick.delete("PIONEER/rekordbox/export.pdb")).isTrue()
        assertThat(stick.exists("PIONEER/rekordbox/export.pdb")).isFalse()
        assertThat(stick.delete("PIONEER/rekordbox/export.pdb")).isFalse()
    }

    @Test
    fun anExportThroughTheProviderWritesTheWholeStickAndAppends() = runTest {
        val music = File(context.cacheDir, "test-source").apply { deleteRecursively(); mkdirs() }
        File(music, "one.aiff").writeBytes(aiff(frames = 44_100))
        File(music, "two.aiff").writeBytes(aiff(frames = 22_050))
        stick.openWrite("Music/one.aiff").use { it.write(File(music, "one.aiff").readBytes()) }
        stick.openWrite("Music/Nested/two.aiff").use { it.write(File(music, "two.aiff").readBytes()) }
        stick.openWrite("Music/notes.txt").use { it.write("hi".toByteArray()) }
        val sources = SafExportSources(context, NoTags) { null }
        val exporter = StickExporter(
            sources,
            CachedTrackAnalyzer(OneBeatAnalyzer, NoCache, "test"),
            Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC),
            NoConversions,
        )

        val listed = sources.listAudioFiles(tree.toString())
        val inspected = listed.map { sources.inspect(it) }

        // Path order: /Music/Nested/two.aiff sorts before /Music/one.aiff; notes.txt is not audio.
        assertThat(inspected.map { it.fileName }).containsExactly("two.aiff", "one.aiff").inOrder()
        val picked = inspected.sortedBy { it.fileName }
        assertThat(picked.map { it.audio.container to it.audio.bitsPerSample }).containsExactly(
            AudioContainer.AIFF to 16,
            AudioContainer.AIFF to 16,
        )

        val first = exporter.export(stick, ExportRequest(picked.take(1), "First")) as ExportResult.Completed
        val second = exporter.export(stick, ExportRequest(picked, "Second")) as ExportResult.Completed

        assertThat(first.exported).hasSize(1)
        assertThat(second.exported.map { it.name }).containsExactly("two.aiff")
        assertThat(second.duplicates.map { it.name }).containsExactly("one.aiff")
        assertThat(File(root, "Contents/UnknownArtist/UnknownAlbum/one.aiff").readBytes()).isEqualTo(File(music, "one.aiff").readBytes())
        assertThat(File(root, "PIONEER/rekordbox/export.pdb").length()).isGreaterThan(0)
        assertThat(File(root, "PIONEER/rekordbox").list()!!.toList()).containsExactly("export.pdb", "exportExt.pdb")
        assertThat(File(root, "PIONEER/DEVSETTING.DAT").length()).isEqualTo(DevSettingWriter.write().size.toLong())
        val manifest = ManifestJson.decode(File(root, StickManifest.PATH).readBytes())
        assertThat(manifest.tracks.map { it.title }).containsExactly("one", "two").inOrder()
        assertThat(manifest.playlists.map { it.name }).containsExactly("First", "Second").inOrder()
        manifest.tracks.forEach { assertThat(File(root, it.analyzePath).isFile).isTrue() }
    }

    private fun aiff(frames: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val sound = frames * 4
            out.writeBytes("FORM")
            out.writeInt(4 + 8 + 18 + 8 + 8 + sound)
            out.writeBytes("AIFFCOMM")
            out.writeInt(18)
            out.writeShort(2)
            out.writeInt(frames)
            out.writeShort(16)
            out.write(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
            out.writeBytes("SSND")
            out.writeInt(8 + sound)
            out.writeInt(0)
            out.writeInt(0)
            out.write(ByteArray(sound) { (it % 50).toByte() })
        }
        return bytes.toByteArray()
    }

    private object NoTags : TrackFileInfoReader {
        override suspend fun read(track: Track): TrackFileInfo = throw UnsupportedOperationException("no tags in this test")
    }

    private object NoCache : AnalysisCache {
        override suspend fun get(contentSha256: String, analyzerVersion: String): TrackAnalysis? = null

        override suspend fun put(contentSha256: String, analysis: TrackAnalysis) = Unit

        override suspend fun remove(contentSha256: String, analyzerVersion: String) = Unit

        override suspend fun prune(keepAnalyzerVersion: String) = Unit
    }

    private object OneBeatAnalyzer : TrackAnalyzer {
        override suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit) = TrackAnalysis(
            beatGrid = BeatGrid(120.0, 0.0, 1, 2),
            key = MusicalKey.C_MAJOR,
            waveform = BlueWaveform(ByteArray(150), ByteArray(BlueWaveform.PREVIEW_COLUMNS), ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS)),
            durationMs = 1_000,
            analyzerVersion = "test",
        )
    }
}

/** The tracks of these tests are plain AIFF 16-bit: nothing is converted. */
private object NoConversions : ExportTrackConverter {
    override suspend fun convert(
        source: io.sonora.core.domain.export.SourceFile,
        profile: io.sonora.core.domain.export.DeviceProfile,
        onProgress: (Float) -> Unit,
    ): ConvertedTrack = error("${source.fileName} needs no conversion")

    override fun discardLeftovers() = Unit
}
