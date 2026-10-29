package io.sonora.core.data.conversion

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.saf.SafConversionFiles
import io.sonora.core.data.conversion.saf.SafConversionSourceExpander
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ExpandedSources
import io.sonora.core.domain.port.StoredDocument
import java.io.FileNotFoundException
import java.io.IOException
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** How the SAF adapters cope with providers that fail, return nothing or throw unexpected exceptions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafProviderFailuresTest {

    private val context = RuntimeEnvironment.getApplication()
    private val files = SafConversionFiles(context)
    private val folder = "content://$AUTHORITY/tree/root/document/root"

    @Before
    fun setUp() {
        StubProvider.mode = "rows"
        Robolectric.buildContentProvider(StubProvider::class.java).create(ProviderInfo().apply { authority = AUTHORITY })
    }

    @Test
    fun `listing a folder the provider rejects throws IOException`() = runBlocking<Unit> {
        StubProvider.mode = "illegal"
        assertThrows(IOException::class.java) { runBlocking { files.children(folder) } }

        StubProvider.mode = "null"
        assertThrows(IOException::class.java) { runBlocking { files.children(folder) } }
    }

    @Test
    fun `a revoked grant still reads as SecurityException`() = runBlocking<Unit> {
        StubProvider.mode = "security"
        assertThrows(SecurityException::class.java) { runBlocking { files.children(folder) } }
    }

    @Test
    fun `rows without a document id or name are skipped`() = runBlocking<Unit> {
        val children = files.children(folder)

        assertThat(children.map { it.name }).containsExactly("b.flac")
        assertThat(children.single().uri).endsWith("/document/doc2")
    }

    @Test
    fun `a provider whose create call breaks throws IOException`() = runBlocking<Unit> {
        assertThrows(IOException::class.java) { runBlocking { files.createFile(folder, "x.aiff", "audio/aiff") } }
    }

    @Test
    fun `a copy whose source the provider rejects throws IOException`() = runBlocking<Unit> {
        val source = "content://$AUTHORITY/tree/root/document/doc2"
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            source.toUri(),
            Supplier { throw IllegalArgumentException("Unsupported mode") },
        )

        assertThrows(IOException::class.java) { runBlocking { files.copy(source, "content://$AUTHORITY/tree/root/document/out") } }
    }

    // Source expansion

    private class TreeFiles(private val tree: Map<String, List<StoredDocument>>, private val broken: Set<String>) : ConversionFiles {
        override suspend fun children(folderUri: String): List<StoredDocument> {
            yield() // lets a walk that never ends time out
            if (folderUri in broken) throw SecurityException("revoked")
            return tree[folderUri] ?: throw FileNotFoundException(folderUri)
        }

        override suspend fun findOrCreateFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFile(folderUri: String, name: String, mimeType: String) = error("unused")
        override suspend fun rename(documentUri: String, name: String) = error("unused")
        override suspend fun copy(fromUri: String, toUri: String) = error("unused")
        override suspend fun delete(documentUri: String) = error("unused")
    }

    private val pickedTree = "content://$AUTHORITY/tree/music"
    private val root = "$pickedTree/document/music"

    private fun expandAll(tree: Map<String, List<StoredDocument>>, broken: Set<String> = emptySet()): ExpandedSources =
        runBlocking { withTimeout(3_000) { SafConversionSourceExpander(context, TreeFiles(tree, broken)).expand(listOf(PickedSource.Folder(pickedTree))) } }

    private fun expand(tree: Map<String, List<StoredDocument>>, broken: Set<String> = emptySet()): List<BatchSource> =
        expandAll(tree, broken).sources

    @Test
    fun `subfolders that cannot be listed are counted, so the user learns their files were left out`() {
        val expanded = expandAll(
            mapOf(
                root to listOf(
                    StoredDocument("locked", "Locked", isFolder = true),
                    StoredDocument("gone", "Gone", isFolder = true),
                    StoredDocument("rock", "Rock", isFolder = true),
                ),
                "rock" to listOf(StoredDocument("b", "b.mp3")),
            ),
            broken = setOf("locked"),
        )

        assertThat(expanded.sources).containsExactly(BatchSource("b", "b.mp3", "rock", pickUri = pickedTree))
        assertThat(expanded.skippedFolders).isEqualTo(2)
        assertThat(expand(mapOf(root to listOf(StoredDocument("a", "a.flac")))).size).isEqualTo(1)
        assertThat(expandAll(mapOf(root to listOf(StoredDocument("a", "a.flac")))).skippedFolders).isEqualTo(0)
    }

    @Test
    fun `a subfolder that cannot be listed is skipped, the rest of the pick is kept`() {
        val sources = expand(
            mapOf(
                root to listOf(StoredDocument("a", "a.flac"), StoredDocument("locked", "Locked", isFolder = true), StoredDocument("rock", "Rock", isFolder = true)),
                "rock" to listOf(StoredDocument("b", "b.mp3")),
            ),
            broken = setOf("locked"),
        )

        assertThat(sources).containsExactly(BatchSource("a", "a.flac", root, pickUri = pickedTree), BatchSource("b", "b.mp3", "rock", pickUri = pickedTree)).inOrder()
    }

    @Test
    fun `a folder that lists itself again is walked once`() {
        val sources = expand(
            mapOf(
                root to listOf(StoredDocument("loop", "Loop", isFolder = true)),
                "loop" to listOf(StoredDocument("loop", "Loop", isFolder = true), StoredDocument("c", "c.wav")),
            ),
        )

        assertThat(sources).containsExactly(BatchSource("c", "c.wav", "loop", pickUri = pickedTree))
    }

    @Test
    fun `every file found in a picked folder names that folder as its pick, so its output choice follows the folder`() {
        val sources = expand(
            mapOf(
                root to listOf(StoredDocument("a", "a.flac"), StoredDocument("rock", "Rock", isFolder = true)),
                "rock" to listOf(StoredDocument("b", "b.mp3")),
            ),
        )

        assertThat(sources.map { it.pickUri }).containsExactly(pickedTree, pickedTree)
        assertThat(sources.map { it.profileId }).containsExactly(null, null)
    }

    @Test
    fun `a picked folder that cannot be listed fails the expansion`() {
        assertThrows(SecurityException::class.java) { expand(emptyMap(), broken = setOf(root)) }
    }

    class StubProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
            when (mode) {
                "illegal" -> throw IllegalArgumentException("Unknown URI $uri")
                "security" -> throw SecurityException("revoked")
                "null" -> null
                else -> MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)).apply {
                    addRow(arrayOf<Any?>(null, "no-id.flac", "audio/flac"))
                    addRow(arrayOf<Any?>("doc1", null, "audio/flac"))
                    addRow(arrayOf<Any?>("doc2", "b.flac", "audio/flac"))
                }
            }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle = throw IllegalStateException("provider died")

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            var mode = "rows"
        }
    }

    private companion object {
        const val AUTHORITY = "io.sonora.test.documents"
    }
}
