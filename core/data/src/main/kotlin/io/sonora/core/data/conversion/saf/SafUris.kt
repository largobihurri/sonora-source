package io.sonora.core.data.conversion.saf

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/** Tree and document URIs of the Storage Access Framework, from the strings the domain passes around. */
internal object SafUris {

    /** The tree a tree URI or tree-based document URI belongs to; `null` for plain document and other URIs. */
    fun treeOf(uri: String): Uri? {
        val parsed = uri.toUri()
        if (parsed.scheme != "content" || !isTreeBased(parsed)) return null
        return DocumentsContract.buildTreeDocumentUri(parsed.authority, DocumentsContract.getTreeDocumentId(parsed))
    }

    /** The folder document a folder URI names: a tree's root document, or the document itself. */
    fun folderDocument(uri: String): Uri {
        val parsed = uri.toUri()
        if (!isTreeBased(parsed) || parsed.pathSegments.size >= 4) return parsed
        return DocumentsContract.buildDocumentUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed))
    }

    /** `content://authority/tree/<id>` or `content://authority/tree/<id>/document/<id>`. */
    private fun isTreeBased(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return segments.size >= 2 && segments[0] == "tree" && (segments.size == 2 || segments[2] == "document")
    }

    /**
     * The folder document holding the tree-based document [uri], when its id is a path (external storage:
     * "primary:Music/Rock/a.flac" sits in "primary:Music/Rock") and that folder is still inside the tree; `null` for
     * opaque ids, the tree's root, plain documents and other URIs.
     */
    fun parentFolder(uri: String): String? {
        val parsed = uri.toUri()
        if (parsed.scheme != "content" || !isTreeBased(parsed) || parsed.pathSegments.size < 4) return null
        val treeId = DocumentsContract.getTreeDocumentId(parsed)
        val id = DocumentsContract.getDocumentId(parsed)
        val parentId = when {
            '/' in id -> id.substringBeforeLast('/')
            id.indexOf(':').let { it >= 0 && it < id.lastIndex } -> id.substringBefore(':') + ":"
            else -> return null
        }
        val insideTree = parentId == treeId || parentId.startsWith(if (treeId.endsWith(':')) treeId else "$treeId/")
        if (!insideTree || id == treeId) return null
        return DocumentsContract.buildDocumentUriUsingTree(parsed, parentId).toString()
    }
}
