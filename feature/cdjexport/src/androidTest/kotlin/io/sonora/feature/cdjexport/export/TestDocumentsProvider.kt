package io.sonora.feature.cdjexport.export

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A document provider over a folder of the test app's cache ([rootDirectory]), with tree support (create, rename,
 * delete, child checks), standing in for the system's external storage provider in the SAF adapter tests. Document
 * ids are `root:` plus the path under the folder.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE))
            .apply {
                newRow()
                    .add(Root.COLUMN_ROOT_ID, "root")
                    .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                    .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                    .add(Root.COLUMN_TITLE, "TESTSTICK")
            }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { add(this, documentId) }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply {
            fileOf(parentId).listFiles().orEmpty().sortedBy { it.name }.forEach { add(this, idOf(it)) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val file = File(fileOf(parentId), displayName)
        if (file.exists()) throw IllegalStateException("$displayName exists")
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw IllegalStateException("Cannot delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val before = fileOf(documentId)
        val after = File(before.parentFile, displayName)
        if (after.exists()) throw IllegalStateException("$displayName exists")
        if (!before.renameTo(after)) throw IllegalStateException("Cannot rename $documentId")
        return idOf(after)
    }

    override fun isChildDocument(parentId: String, documentId: String): Boolean =
        documentId == parentId || documentId.startsWith(if (parentId == ROOT_ID) ROOT_ID else "$parentId/")

    private fun add(cursor: MatrixCursor, documentId: String) {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val folder = file.isDirectory
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_ID) "TESTSTICK" else file.name)
        row.add(Document.COLUMN_MIME_TYPE, if (folder) Document.MIME_TYPE_DIR else mimeOf(file.name))
        row.add(Document.COLUMN_SIZE, if (folder) null else file.length())
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            if (folder) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "aif", "aiff" -> "audio/aiff"
        "mp3" -> "audio/mpeg"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun rootDirectory(): File = rootDirectory(requireNotNull(context).cacheDir)

    private fun fileOf(documentId: String): File {
        require(documentId.startsWith(ROOT_ID)) { "Unknown document $documentId" }
        val relative = documentId.removePrefix(ROOT_ID)
        return if (relative.isEmpty()) rootDirectory() else File(rootDirectory(), relative)
    }

    private fun idOf(file: File): String = ROOT_ID + file.relativeTo(rootDirectory()).path.let { if (it == "") "" else it }

    companion object {
        const val AUTHORITY = "io.sonora.feature.cdjexport.test.documents"
        const val ROOT_ID = "root:"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        fun rootDirectory(cacheDir: File): File = File(cacheDir, "test-stick").apply { mkdirs() }
    }
}
