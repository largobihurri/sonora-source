package io.sonora.core.data.archive

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ExtractedFile
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.StoredDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reading back an extracted folder, with the documents as `file://` URIs listed by a fake [ConversionFiles]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafImportedFoldersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manifest = ArchiveManifest(ArchiveKind.PLAYLIST, "Mix", listOf(ArchiveEntry("01 - A - One.flac", "One", "A", "Mix")))

    private inner class Listing(private val documents: List<StoredDocument>) : ConversionFiles {
        override suspend fun children(folderUri: String): List<StoredDocument> = documents
        override suspend fun findOrCreateFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFile(folderUri: String, name: String, mimeType: String) = error("unused")
        override suspend fun rename(documentUri: String, name: String) = error("unused")
        override suspend fun copy(fromUri: String, toUri: String) = error("unused")
        override suspend fun delete(documentUri: String) = false
    }

    private fun document(name: String, text: String = "x"): StoredDocument =
        StoredDocument(Uri.fromFile(File(temp.root, name).apply { writeText(text) }).toString(), name)

    private fun reader(vararg documents: StoredDocument) =
        SafImportedFolders(RuntimeEnvironment.getApplication(), Listing(documents.toList()))

    @Test
    fun `a Sonora folder gives its files and its manifest`() = runBlocking<Unit> {
        val song = document("01 - A - One.flac")
        val json = document(ArchiveManifest.FILE_NAME, ArchiveManifestJson.encode(manifest))
        val sub = StoredDocument("file:///nowhere/sub", "sub", isFolder = true)

        val folder = reader(song, json, sub).read("folder")

        assertThat(folder.manifest).isEqualTo(manifest)
        assertThat(folder.files).containsExactly(ExtractedFile(song.name, song.uri))
    }

    @Test
    fun `a folder without a readable manifest gives only its files`() = runBlocking<Unit> {
        val song = document("track.mp3")

        assertThat(reader(song).read("folder").manifest).isNull()
        assertThat(reader(song, document(ArchiveManifest.FILE_NAME, "{ not json")).read("folder").manifest).isNull()
    }

    @Test
    fun `a manifest too big to be Sonora's is not read`() = runBlocking<Unit> {
        val huge = document(ArchiveManifest.FILE_NAME, " ".repeat(ArchiveManifest.MAX_BYTES + 1) + ArchiveManifestJson.encode(manifest))

        assertThat(reader(huge).read("folder").manifest).isNull()
    }
}
