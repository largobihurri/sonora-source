package io.sonora.core.data.conversion.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.StoredDocument
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [ConversionFiles] over DocumentsContract with tree-based document URIs, so every folder it returns can be listed
 * and written through the grant of the tree it belongs to.
 *
 * Providers fail in many ways: `null` results, [SecurityException] for a revoked grant, `FileNotFoundException`,
 * `IllegalArgumentException` or `IllegalStateException` for URIs or modes they reject, and runtime exceptions when
 * they die mid-call. As the port promises, everything but [SecurityException] reaches the domain as [IOException].
 */
class SafConversionFiles @Inject constructor(@ApplicationContext context: Context) : ConversionFiles {

    private val resolver: ContentResolver = context.contentResolver

    override suspend fun children(folderUri: String): List<StoredDocument> = provider("list $folderUri") {
        val folder = SafUris.folderDocument(folderUri)
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val cursor = resolver.query(listing, PROJECTION, null, null, null) ?: throw IOException("Cannot list $folderUri")
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val documentId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mimeType = it.getString(2)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(folder, documentId)
                    add(StoredDocument(uri.toString(), name, mimeType == Document.MIME_TYPE_DIR, mimeType))
                }
            }
        }
    }

    override suspend fun findOrCreateFolder(parentUri: String, name: String): StoredDocument =
        children(parentUri).firstOrNull { it.isFolder && it.name.equals(name, ignoreCase = true) }
            ?: createFolder(parentUri, name)

    override suspend fun createFolder(parentUri: String, name: String): StoredDocument =
        create(parentUri, Document.MIME_TYPE_DIR, name).copy(isFolder = true)

    override suspend fun createFile(folderUri: String, name: String, mimeType: String): StoredDocument {
        // Providers append an extension when the name's own does not match the MIME type ("x.aiff" as audio/aiff may
        // become "x.aiff.aif"); the type the platform maps the extension to keeps the name as asked.
        val extension = name.substringAfterLast('.', "").lowercase()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: mimeType
        return create(folderUri, type, name)
    }

    private suspend fun create(parentUri: String, mimeType: String, name: String) = provider("create $name in $parentUri") {
        val parent = SafUris.folderDocument(parentUri)
        val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("Cannot create $name in $parentUri")
        StoredDocument(created.toString(), displayName(created) ?: name, mimeType = mimeType)
    }

    override suspend fun rename(documentUri: String, name: String): StoredDocument? = withContext(Dispatchers.IO) {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, documentUri.toUri(), name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // no rename support (UnsupportedOperationException across the binder) or a refused name
        } ?: return@withContext null
        StoredDocument(renamed.toString(), displayName(renamed) ?: name)
    }

    override suspend fun copy(fromUri: String, toUri: String) = provider("copy $fromUri to $toUri") {
        val input = resolver.openInputStream(fromUri.toUri()) ?: throw IOException("Cannot read $fromUri")
        input.use { source ->
            val output = resolver.openOutputStream(toUri.toUri(), "wt") ?: throw IOException("Cannot write $toUri")
            output.use { target ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    target.write(buffer, 0, read)
                }
            }
        }
    }

    override suspend fun delete(documentUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri.toUri())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Runs a provider call on the I/O dispatcher with the port's failure contract: cancellation, [IOException] and
     * [SecurityException] pass through, any other exception (a provider rejecting the URI or mode, or dying) becomes
     * an [IOException] about what was being done.
     */
    private suspend fun <T> provider(action: String, call: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot $action (${e.message ?: e.javaClass.simpleName})", e)
        }
    }

    private fun displayName(document: Uri): String? = try {
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val COPY_BUFFER = 256 * 1024
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    }
}
