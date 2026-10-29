package io.sonora.core.transcoder.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import javax.inject.Inject

/** A writable, seekable output for one conversion. [commit] after a successful write, then [close]. */
interface OutputTarget : Closeable {
    val channel: SeekableByteChannel

    /** Makes the written bytes durable at the destination. */
    fun commit()
}

/**
 * Opens a document URI (SAF `content://` or `file://`) as an [OutputTarget], truncating it. Providers that hand out
 * a seekable descriptor are written in place; for pipes (some cloud providers) or providers without `rwt`, the
 * output is spooled to a cache file and copied to the document on [OutputTarget.commit].
 */
class OutputTargets @Inject constructor(@ApplicationContext private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    fun open(outputUri: String): OutputTarget {
        val uri = outputUri.toUri()
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rwt")
        } catch (e: SecurityException) {
            throw IOException("No write access to $outputUri", e)
        } catch (_: IllegalArgumentException) {
            null // mode not supported: spool
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (descriptor != null) {
            if (isSeekable(descriptor)) return DirectTarget(descriptor)
            descriptor.close()
        }
        return SpooledTarget(resolver, uri, context.cacheDir)
    }

    private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean = try {
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (_: ErrnoException) {
        false
    }

    private class DirectTarget(private val descriptor: ParcelFileDescriptor) : OutputTarget {
        private val stream = FileOutputStream(descriptor.fileDescriptor)
        override val channel: FileChannel = stream.channel

        override fun commit() = channel.force(true)

        override fun close() {
            try {
                stream.close()
            } finally {
                descriptor.close()
            }
        }
    }

    private class SpooledTarget(private val resolver: ContentResolver, private val uri: Uri, cacheDir: File) : OutputTarget {
        private val file: File
        private val access: RandomAccessFile

        init {
            val (spool, opened) = openSpoolFile(cacheDir)
            file = spool
            access = opened
        }

        override val channel: FileChannel = access.channel

        override fun commit() {
            channel.force(false)
            val output = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: SecurityException) {
                throw IOException("No write access to $uri", e)
            } ?: throw IOException("No output stream for $uri")
            output.use { out -> FileInputStream(file).use { it.copyTo(out, COPY_BUFFER) } }
        }

        override fun close() {
            try {
                access.close()
            } finally {
                file.delete()
            }
        }
    }

    private companion object {
        const val COPY_BUFFER = 256 * 1024
    }
}

/**
 * A new `.part` file in [cacheDir] opened with [open]; the file is deleted when opening fails, so a full disk or a
 * revoked cache never leaves spool files behind.
 */
internal fun openSpoolFile(
    cacheDir: File,
    open: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
): Pair<File, RandomAccessFile> {
    val file = File.createTempFile("convert-", ".part", cacheDir)
    return try {
        file to open(file)
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
}
