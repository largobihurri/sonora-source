package io.sonora.core.domain.port

/** A document or folder in shared storage (SAF). */
data class StoredDocument(val uri: String, val name: String, val isFolder: Boolean = false, val mimeType: String? = null)

/**
 * The document operations the converter needs on shared storage. Folder URIs may be tree URIs (a picked folder's
 * root) or document URIs below a tree. Failures throw `IOException` or `SecurityException` unless stated.
 */
interface ConversionFiles {

    suspend fun children(folderUri: String): List<StoredDocument>

    /** The child folder called [name] (ignoring case), created when missing. */
    suspend fun findOrCreateFolder(parentUri: String, name: String): StoredDocument

    suspend fun createFolder(parentUri: String, name: String): StoredDocument

    /** A new empty file. The storage may adjust [name]; the returned document has the real one. */
    suspend fun createFile(folderUri: String, name: String, mimeType: String): StoredDocument

    /** Renames a document; `null` when the storage cannot rename. The result may have a new URI. */
    suspend fun rename(documentUri: String, name: String): StoredDocument?

    /** Overwrites [toUri] with the bytes of [fromUri]. */
    suspend fun copy(fromUri: String, toUri: String)

    /** Deletes a document; false when it could not be deleted (never throws). */
    suspend fun delete(documentUri: String): Boolean
}
