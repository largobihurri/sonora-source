package io.sonora.core.data.archive

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.StoredDocument
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The ports' adapters over the content resolver, with songs and archives as `file://` URIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZipArchiveAdaptersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val compressor = ZipArchiveCompressor(context)

    private object NoFolders : ConversionFiles {
        override suspend fun children(folderUri: String): List<StoredDocument> = error("unused")
        override suspend fun findOrCreateFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFile(folderUri: String, name: String, mimeType: String) = error("unused")
        override suspend fun rename(documentUri: String, name: String) = error("unused")
        override suspend fun copy(fromUri: String, toUri: String) = error("unused")
        override suspend fun delete(documentUri: String) = false
    }

    /**
     * The `file:///` URI Android hands over for this file. `Uri.fromFile` keeps the host's separators, so on Windows it
     * gives `file:C%3A%5C…` with no `/` at all, which no device ever produces.
     */
    private fun File.uri(): String = "file://${toURI().rawPath}"

    private fun song(name: String, bytes: ByteArray): Track {
        val file = File(temp.root, name).apply { writeBytes(bytes) }
        return Track(
            id = 1, uri = file.uri(), title = name.substringBefore('.'), artist = "Band",
            album = "Record", albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null, year = null,
            albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = bytes.size.toLong(),
        )
    }

    private fun compress(tracks: List<Track>, password: String?, output: File) = runBlocking {
        compressor.compress(CompressionRequest(ArchiveKind.PLAYLIST, "Mix", tracks), password?.toCharArray(), output.path) {}
    }

    @Test
    fun `songs are packed into the output file, encrypted with the password`() {
        val bytes = Random(3).nextBytes(50_000)
        val output = File(temp.newFolder("archives"), "Mix.zip")
        val outcome = compress(listOf(song("One.flac", bytes), song("Two.flac", bytes)), "Correct-Horse-42", output)

        val archive = (outcome as ArchiveOutcome.Done<CompressedArchive>).value
        assertThat(archive).isEqualTo(CompressedArchive(output.path, "Mix.zip", output.length(), 2, encrypted = true))
        assertThat(ZipFile(output).fileHeaders.map { it.fileName })
            .containsExactly("Mix/sonora.json", "Mix/01 - Band - One.flac", "Mix/02 - Band - Two.flac").inOrder()
        assertThat(File("${output.path}.part").exists()).isFalse()
    }

    @Test
    fun `a missing song fails with its title and leaves no file behind`() {
        val gone = song("Gone.flac", ByteArray(10)).also { File(Uri.parse(it.uri).path!!).delete() }
        val output = File(temp.newFolder("archives"), "Mix.zip")
        val outcome = compress(listOf(gone), null, output)

        assertThat(outcome).isEqualTo(ArchiveOutcome.Failed(ArchiveFailure.UnreadableSource("Gone")))
        assertThat(output.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `nothing to pack is no music`() {
        assertThat(compress(emptyList(), null, File(temp.root, "x.zip"))).isEqualTo(ArchiveOutcome.Failed(ArchiveFailure.NoMusic))
    }

    @Test
    fun `an archive is inspected straight from its uri`() {
        val output = File(temp.newFolder("archives"), "Mix.zip")
        compress(listOf(song("One.flac", ByteArray(100))), "Correct-Horse-42", output)
        val importer = ZipArchiveImporter(context, NoFolders)

        val summary = runBlocking { importer.inspect(output.uri()) }
        assertThat(summary).isEqualTo(ArchiveOutcome.Done(ArchiveSummary(encrypted = true, manifest = null)))
        val missing = runBlocking { importer.inspect(File(temp.root, "none.zip").uri()) }
        assertThat(missing).isEqualTo(ArchiveOutcome.Failed(ArchiveFailure.UnreadableSource("none.zip")))
    }
}
