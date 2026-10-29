package io.sonora.feature.cdjexport.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.export.StickEntry
import io.sonora.core.domain.export.StickFileSystem
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.domain.export.StickLocator
import io.sonora.core.domain.export.StickVolume
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject

/**
 * Opens a stick from a storage tree picked with `ACTION_OPEN_DOCUMENT_TREE`. The players read the stick's top level,
 * so only the root of a removable volume of the system's external storage provider (document id `XXXX-XXXX:`) counts
 * as a stick. Its free space comes from the volume's mount point ([StatFs]), since the provider's roots are not
 * readable by other apps.
 */
class SafStickLocator @Inject constructor(@ApplicationContext private val context: Context) : StickLocator {

    override fun open(treeUri: String): StickVolume {
        val uri = treeUri.toUri()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            throw StickIoException("Not a storage tree: $treeUri", e)
        }
        val volumeId = rootId.substringBefore(':')
        val isVolumeRoot = uri.authority == EXTERNAL_STORAGE && rootId == "$volumeId:" && volumeId != PRIMARY
        val volume = storageVolume(volumeId)
        val fileSystem = SafStickFileSystem(context.contentResolver, uri, rootId) { freeBytes(volume, volumeId) }
        // Listing the root proves the grant still works before the user goes on.
        fileSystem.exists("PIONEER")
        val name = volume?.getDescription(context) ?: displayName(uri, rootId) ?: volumeId
        return StickVolume(name, isRemovableRoot = isVolumeRoot && volume?.isRemovable != false, fileSystem = fileSystem)
    }

    /** Only a hint for the picker: any failure to read the volumes just lets the picker choose. */
    override fun pickerStartUri(): String? = try {
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().map {
            VolumeSummary(it.uuid, it.isRemovable, it.state == Environment.MEDIA_MOUNTED)
        }
        removableRootDocumentId(volumes)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, it).toString() }
    } catch (e: RuntimeException) {
        null
    }

    private fun storageVolume(volumeId: String): StorageVolume? =
        context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }

    private fun freeBytes(volume: StorageVolume?, volumeId: String): Long? {
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume?.directory else null
        val path = directory ?: File("/storage/$volumeId")
        return try {
            StatFs(path.path).availableBytes
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun displayName(tree: Uri, rootId: String): String? = try {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val PRIMARY = "primary"
    }
}

/**
 * The system's folder picker opened at the root of the removable volume [uuid]
 * ([StorageVolume.createOpenDocumentTreeIntent]), so picking the stick is one tap; null when that volume is not there.
 */
fun volumeRootPickerIntent(context: Context, uuid: String): Intent? = try {
    context.getSystemService(StorageManager::class.java)?.storageVolumes
        ?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
        ?.createOpenDocumentTreeIntent()
} catch (e: RuntimeException) {
    null
}

/** What [removableRootDocumentId] needs to know of a [StorageVolume]. */
internal data class VolumeSummary(val uuid: String?, val removable: Boolean, val mounted: Boolean)

/**
 * The document id (`XXXX-XXXX:`) of the root of the only mounted removable volume, or null when there is none or
 * several: apps cannot tell an SD card from a stick, so with both mounted the picker is not pointed at either.
 */
internal fun removableRootDocumentId(volumes: List<VolumeSummary>): String? =
    volumes.filter { it.removable && it.mounted && it.uuid != null }.singleOrNull()?.let { "${it.uuid}:" }

/**
 * [StickFileSystem] over a document tree, through [DocumentsContract]: folders are listed once and cached (by name
 * without regard to case, as FAT compares them), files are created as `application/octet-stream` so the provider
 * keeps their names as given, and a replace is a delete then a rename (the provider refuses to rename onto an
 * existing name, so there is a short moment without the target). Every failure is a [StickIoException].
 */
internal class SafStickFileSystem(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val rootId: String,
    private val free: () -> Long?,
) : StickFileSystem {

    private class Entry(val documentId: String, val name: String, val isFolder: Boolean)

    /** The listed children of each folder, by document id, then by upper-cased name. */
    private val folders = HashMap<String, MutableMap<String, Entry>>()

    override fun freeBytes(): Long? = free()

    @Synchronized
    override fun exists(path: String): Boolean = stick("Looking for $path") { find(path) != null }

    @Synchronized
    override fun sizeOf(path: String): Long? = stick("Reading the size of $path") {
        val entry = find(path)?.takeUnless { it.isFolder } ?: return@stick null
        resolver.query(uriOf(entry.documentId), arrayOf(Document.COLUMN_SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    @Synchronized
    override fun read(path: String): ByteArray? = stick("Reading $path") {
        val entry = find(path)?.takeUnless { it.isFolder } ?: return@stick null
        (resolver.openInputStream(uriOf(entry.documentId)) ?: throw IOException("No stream for $path")).use { it.readBytes() }
    }

    @Synchronized
    override fun openWrite(path: String): OutputStream = stick("Opening $path") {
        val parentId = folderId(parentOf(path), create = true)!!
        val name = nameOf(path)
        val entry = children(parentId)[key(name)] ?: create(parentId, name, FILE_MIME)
        if (entry.isFolder) throw IOException("$path is a folder")
        val stream = resolver.openOutputStream(uriOf(entry.documentId), "wt") ?: throw IOException("No stream for $path")
        StickStream(stream, path)
    }

    @Synchronized
    override fun replace(source: String, target: String) = stick("Replacing $target") {
        require(parentOf(source).equals(parentOf(target), ignoreCase = true)) { "replace within one folder only" }
        val parentId = folderId(parentOf(source), create = false) ?: throw IOException("No folder for $source")
        val siblings = children(parentId)
        val moving = siblings[key(nameOf(source))] ?: throw IOException("No $source")
        siblings[key(nameOf(target))]?.let { old ->
            if (!DocumentsContract.deleteDocument(resolver, uriOf(old.documentId))) throw IOException("Cannot delete $target")
            siblings.remove(key(old.name))
        }
        val renamed = DocumentsContract.renameDocument(resolver, uriOf(moving.documentId), nameOf(target))
        siblings.remove(key(moving.name))
        val id = renamed?.let(DocumentsContract::getDocumentId) ?: moving.documentId
        siblings[key(nameOf(target))] = Entry(id, nameOf(target), isFolder = false)
    }

    @Synchronized
    override fun delete(path: String): Boolean = stick("Deleting $path") {
        val parentId = folderId(parentOf(path), create = false) ?: return@stick false
        val entry = children(parentId)[key(nameOf(path))] ?: return@stick false
        // A folder goes with everything in it (the provider deletes it recursively).
        val deleted = DocumentsContract.deleteDocument(resolver, uriOf(entry.documentId))
        if (deleted) {
            children(parentId).remove(key(entry.name))
            if (entry.isFolder) forgetListings(entry.documentId)
        }
        deleted
    }

    /**
     * Drops the cached listings of a deleted folder and of every folder under it: a folder made again at the same path
     * gets the same document id from the storage provider, and must not show what the deleted one held.
     */
    private fun forgetListings(folderId: String) {
        val listing = folders.remove(folderId) ?: return
        listing.values.filter { it.isFolder }.forEach { forgetListings(it.documentId) }
    }

    @Synchronized
    override fun list(path: String): List<StickEntry>? = stick("Listing $path") {
        val id = folderId(path, create = false) ?: return@stick null
        children(id).values.map { StickEntry(it.name, it.isFolder) }
    }

    private fun find(path: String): Entry? {
        val parentId = folderId(parentOf(path), create = false) ?: return null
        return children(parentId)[key(nameOf(path))]
    }

    /** The document id of the folder at [path] ("" is the root), creating missing folders when [create]. */
    private fun folderId(path: String, create: Boolean): String? {
        var id = rootId
        for (name in path.split('/').filter { it.isNotEmpty() }) {
            val entry = children(id)[key(name)]
                ?: if (create) create(id, name, Document.MIME_TYPE_DIR) else return null
            if (!entry.isFolder) throw IOException("$name in $path is a file")
            id = entry.documentId
        }
        return id
    }

    private fun children(folderId: String): MutableMap<String, Entry> = folders.getOrPut(folderId) {
        val listing = HashMap<String, Entry>()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId), columns, null, null, null)
            ?: throw IOException("Cannot list $folderId")
        cursor.use {
            while (it.moveToNext()) {
                val entry = Entry(it.getString(0), it.getString(1), it.getString(2) == Document.MIME_TYPE_DIR)
                listing[key(entry.name)] = entry
            }
        }
        listing
    }

    /** Creates [name] in the folder [parentId]; refuses a provider that picked another name (it was taken). */
    private fun create(parentId: String, name: String, mimeType: String): Entry {
        val uri = DocumentsContract.createDocument(resolver, uriOf(parentId), mimeType, name)
            ?: throw IOException("Cannot create $name")
        val id = DocumentsContract.getDocumentId(uri)
        val actual = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        if (!actual.equals(name, ignoreCase = true)) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw IOException("The stick named $name \"$actual\"")
        }
        val entry = Entry(id, actual, isFolder = mimeType == Document.MIME_TYPE_DIR)
        children(parentId)[key(actual)] = entry
        if (entry.isFolder) folders[id] = HashMap()
        return entry
    }

    private fun uriOf(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private fun key(name: String): String = name.uppercase(Locale.ROOT)

    private inline fun <T> stick(what: String, block: () -> T): T = try {
        block()
    } catch (e: StickIoException) {
        throw e
    } catch (e: Exception) {
        // IOException, SecurityException (grant revoked), IllegalStateException/IllegalArgumentException (provider
        // gone, volume unmounted): all mean the stick cannot be used right now.
        throw StickIoException("$what failed: ${e.message}", e)
    }

    /** Reports write failures as the stick's. */
    private class StickStream(out: OutputStream, private val path: String) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = wrap { out.write(b, off, len) }

        override fun write(b: Int) = wrap { out.write(b) }

        override fun flush() = wrap { out.flush() }

        override fun close() = wrap { out.close() }

        private inline fun wrap(block: () -> Unit) = try {
            block()
        } catch (e: StickIoException) {
            throw e
        } catch (e: IOException) {
            throw StickIoException("Writing $path failed: ${e.message}", e)
        }
    }

    private companion object {
        const val FILE_MIME = "application/octet-stream"
    }
}
