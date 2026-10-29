package io.sonora.core.data.library.edit

import android.annotation.SuppressLint
import android.util.Log
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.tags.TagChanges
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** The document whose tags are rewritten, as the rewrite reaches it (a SAF document in the app). */
internal interface TagDocument {
    /** Its size now, when known; only sizes the working copies (a provider may report a cached size). */
    fun size(): Long?

    /** Writes its whole content to [out]. */
    fun copyTo(out: OutputStream)

    /**
     * Opens it for writing from scratch: truncated to nothing once this returns. A throw from here means the
     * document was not touched (e.g. a [SecurityException] for a read-only grant).
     */
    fun truncateAndOpen(): OutputStream
}

internal sealed interface RewriteResult {
    /** Replaced and verified; [written] is what the new contents read back as. */
    data class Done(val written: FileTags) : RewriteResult

    data class Failed(val failure: TagEditFailure) : RewriteResult
}

/**
 * Rewrites a document's tags without ever leaving it half-written: the document is copied into [workDir] twice (a
 * pristine backup and a working copy), the tags are written into the working copy, which is read back and checked
 * against the changes ([TagVerification]); only then are the document's contents replaced. When replacing fails part
 * way the backup is written back. Both copies are deleted afterwards.
 */
internal class SafeTagRewrite(private val workDir: File, private val codec: TagCodec) {

    // usableSpace leaves out cache the system could clear for us: the check errs on the safe side.
    @SuppressLint("UsableSpace")
    fun rewrite(document: TagDocument, readerKey: String, changes: TagChanges): RewriteResult {
        workDir.mkdirs() // a missing directory reports no usable space
        val size = try {
            document.size()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the file to retag", e)
            return failed(TagEditFailure.UNREADABLE)
        }
        if (size != null && workDir.usableSpace < size * 2 + SPACE_MARGIN_BYTES) return failed(TagEditFailure.NOT_ENOUGH_SPACE)
        // Created inside the try, so a failure creating the second copy still deletes the first.
        var backup: File? = null
        var work: File? = null
        try {
            backup = File.createTempFile("original", ".$readerKey", workDir)
            work = File.createTempFile("edited", ".$readerKey", workDir)
            val before = try {
                backup.outputStream().use(document::copyTo)
                codec.read(backup, readerKey)
            } catch (e: Exception) {
                if (e is SecurityException) return failed(TagEditFailure.READ_ONLY_LOCATION)
                Log.w(TAG, "Cannot read the file to retag", e)
                return failed(TagEditFailure.UNREADABLE)
            }
            try {
                backup.copyTo(work, overwrite = true)
                codec.write(work, readerKey, changes)
            } catch (e: Exception) {
                Log.w(TAG, "Writing the tags into the working copy failed", e)
                return failed(TagEditFailure.NOT_SAVED)
            }
            val written = try {
                codec.read(work, readerKey).takeIf { TagVerification.holds(before, it, changes) }
            } catch (e: Exception) {
                Log.w(TAG, "The retagged copy does not parse", e)
                null
            } ?: return failed(TagEditFailure.NOT_VERIFIED)
            return replace(document, work, backup)?.let(::failed) ?: RewriteResult.Done(written)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot create the working copies", e)
            return failed(TagEditFailure.NOT_SAVED)
        } catch (e: LinkageError) {
            Log.w(TAG, "Tag writer unavailable", e)
            return failed(TagEditFailure.NOT_SAVED)
        } finally {
            backup?.delete()
            work?.delete()
        }
    }

    /** Puts [work] into [document]; on a failure part way, [backup] goes back in. Returns the failure, if any. */
    private fun replace(document: TagDocument, work: File, backup: File): TagEditFailure? {
        val out = try {
            document.truncateAndOpen()
        } catch (e: Exception) {
            // Not opened, so not truncated: the document is as it was.
            Log.w(TAG, "Cannot open the file for writing", e)
            return if (e is SecurityException) TagEditFailure.READ_ONLY_LOCATION else TagEditFailure.NOT_SAVED
        }
        try {
            writeAll(out, work)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Replacing the file failed; putting the original back", e)
        }
        return try {
            writeAll(document.truncateAndOpen(), backup)
            TagEditFailure.NOT_SAVED
        } catch (e: Exception) {
            Log.e(TAG, "Restoring the original failed; the file may be damaged", e)
            TagEditFailure.INTERRUPTED
        }
    }

    /**
     * Copies [source] into [out] and checks the file then holds exactly that many bytes, which catches a provider that
     * ignores the truncation and leaves old bytes past the new end. The length is read through the descriptor just
     * written, not asked of the document again: a provider may answer with a size it cached before the write, which
     * would fail a good write and then its restore ("the file may be damaged").
     */
    private fun writeAll(out: OutputStream, source: File) {
        out.use { stream ->
            source.inputStream().use { it.copyTo(stream) }
            val held = heldLength(stream)
            if (held != null && held != source.length()) throw IOException("Wrote ${source.length()} bytes, the file holds $held")
        }
    }

    /** The length of the file [stream] writes to, from its own descriptor; `null` when no seekable file is behind it. */
    private fun heldLength(stream: OutputStream): Long? {
        val channel = (stream as? FileOutputStream)?.channel ?: return null
        return try {
            channel.position() // throws for a pipe or a socket, whose size means nothing
            channel.size()
        } catch (_: IOException) {
            null
        }
    }

    private fun failed(failure: TagEditFailure) = RewriteResult.Failed(failure)

    private companion object {
        const val TAG = "SonoraTagEdit"

        /** Room left besides the two copies, so a save never fills the storage. */
        const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
