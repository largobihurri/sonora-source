package io.sonora.core.data.archive

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.StructStatVfs
import androidx.core.net.toUri
import io.sonora.core.domain.conversion.batch.BatchNaming
import io.sonora.core.domain.port.ConversionFiles
import java.io.IOException

/**
 * Extraction into a shared-storage folder: a new folder in [destination] (a free name, never an existing folder),
 * documents created through [ConversionFiles] (DocumentsContract, as the converter writes), and their bytes through
 * a file descriptor, which also tells the free space of the storage behind it.
 */
internal class SafExtractionSink(
    private val resolver: ContentResolver,
    private val files: ConversionFiles,
    private val destination: String,
) : ExtractionSink {

    private var rootUri: String? = null

    /** Folders created below the root, by their lowercase segments (the empty list is the root). */
    private val folders = mutableMapOf<List<String>, String>()
    private var available: Long? = null

    override suspend fun createRoot(name: String): String {
        val taken = files.children(destination).map { it.name }
        val root = files.createFolder(destination, BatchNaming.uniqueFolderName(name, taken))
        rootUri = root.uri
        folders[emptyList()] = root.uri
        return root.uri
    }

    override suspend fun availableBytes(): Long? = available

    override suspend fun createFile(segments: List<String>): CreatedFile {
        val parent = folderFor(segments.dropLast(1))
        val document = files.createFile(parent, segments.last(), OCTET_STREAM)
        val descriptor = resolver.openFileDescriptor(document.uri.toUri(), "w")
            ?: throw IOException("Cannot write ${document.uri}")
        if (available == null) available = freeBytes(descriptor)
        return CreatedFile(document.uri, ParcelFileDescriptor.AutoCloseOutputStream(descriptor))
    }

    override suspend fun discard() {
        rootUri?.let { files.delete(it) }
    }

    private suspend fun folderFor(path: List<String>): String {
        var uri = checkNotNull(folders[emptyList()]) { "No extraction folder yet" }
        for (depth in 1..path.size) {
            val key = path.take(depth).map { it.lowercase() }
            uri = folders[key] ?: files.createFolder(uri, path[depth - 1]).uri.also { folders[key] = it }
        }
        return uri
    }

    /**
     * Free bytes of the file system behind [descriptor]; `null` for providers that hand out pipes or sockets, and
     * whenever the system cannot tell: the probe is optional and never fails the extraction.
     */
    private fun freeBytes(descriptor: ParcelFileDescriptor): Long? = try {
        val stats: StructStatVfs? = Os.fstatvfs(descriptor.fileDescriptor)
        if (stats != null && stats.f_blocks > 0) stats.f_bavail * stats.f_frsize else null
    } catch (_: ErrnoException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
