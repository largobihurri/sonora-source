package io.sonora.core.data.archive

import android.net.Uri
import com.google.common.truth.Truth.assertThat
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

/** The shared-storage extraction sink over a [ConversionFiles] that keeps `file://` documents in a temporary folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafExtractionSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resolver = RuntimeEnvironment.getApplication().contentResolver

    /** Documents as plain files; counts the folders it was asked to create. */
    private class FileDocuments : ConversionFiles {
        val foldersCreated = mutableListOf<String>()

        private fun file(uri: String) = File(checkNotNull(Uri.parse(uri).path))
        private fun document(file: File) = StoredDocument(Uri.fromFile(file).toString(), file.name, file.isDirectory)

        override suspend fun children(folderUri: String): List<StoredDocument> = file(folderUri).listFiles().orEmpty().map(::document)
        override suspend fun findOrCreateFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFolder(parentUri: String, name: String): StoredDocument {
            foldersCreated += name
            return document(File(file(parentUri), name).apply { check(mkdir()) { "exists: $this" } })
        }
        override suspend fun createFile(folderUri: String, name: String, mimeType: String): StoredDocument =
            document(File(file(folderUri), name).apply { check(createNewFile()) { "exists: $this" } })
        override suspend fun rename(documentUri: String, name: String) = error("unused")
        override suspend fun copy(fromUri: String, toUri: String) = error("unused")
        override suspend fun delete(documentUri: String): Boolean = file(documentUri).deleteRecursively()
    }

    private val documents = FileDocuments()
    private val destination by lazy { temp.newFolder("Music") }
    private val sink by lazy { SafExtractionSink(resolver, documents, Uri.fromFile(destination).toString()) }

    @Test
    fun `the new folder never reuses an existing one`() = runBlocking {
        File(destination, "Record").mkdir()
        File(destination, "Record/old.flac").writeText("keep")

        val root = sink.createRoot("Record")

        assertThat(File(Uri.parse(root).path!!).name).isEqualTo("Record (2)")
        assertThat(File(destination, "Record/old.flac").readText()).isEqualTo("keep")
    }

    @Test
    fun `files are written through their descriptor, and subfolders are made once whatever their case`() = runBlocking {
        sink.createRoot("Record")

        val first = sink.createFile(listOf("Disc 1", "01.flac"))
        first.output.use { it.write(byteArrayOf(1, 2, 3)) }
        sink.createFile(listOf("disc 1", "02.flac")).output.use { it.write(byteArrayOf(4)) }
        sink.createFile(listOf("03.flac")).output.close()

        assertThat(File(Uri.parse(first.uri).path!!).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(destination, "Record/Disc 1").list()!!.toList()).containsExactly("01.flac", "02.flac")
        assertThat(File(destination, "Record/03.flac").isFile).isTrue()
        assertThat(documents.foldersCreated).containsExactly("Record", "Disc 1").inOrder()
    }

    @Test
    fun `free space is unknown before the first file`() = runBlocking {
        sink.createRoot("Record")
        assertThat(sink.availableBytes()).isNull()
        sink.createFile(listOf("01.flac")).output.close()
        // Known now, or null where the file system does not tell: never a failure.
        sink.availableBytes()?.let { assertThat(it).isAtLeast(0L) }
        Unit
    }

    @Test
    fun `discarding removes only the new folder`() = runBlocking {
        File(destination, "Other").mkdir()
        sink.discard() // nothing made yet: nothing to do
        sink.createRoot("Record")
        sink.createFile(listOf("Disc 1", "01.flac")).output.use { it.write(1) }

        sink.discard()

        assertThat(destination.list()!!.toList()).containsExactly("Other")
        Unit
    }
}
