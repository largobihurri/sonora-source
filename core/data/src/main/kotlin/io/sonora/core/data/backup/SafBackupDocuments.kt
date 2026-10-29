package io.sonora.core.data.backup

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.port.BackupDocuments
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [BackupDocuments] through the content resolver, for documents picked with the system file picker. */
class SafBackupDocuments @Inject constructor(@ApplicationContext context: Context) : BackupDocuments {

    private val resolver = context.contentResolver

    override suspend fun write(uri: String, text: String) = withContext(Dispatchers.IO) {
        failuresAsIo {
            openForWriting(uri).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    override suspend fun read(uri: String): String = withContext(Dispatchers.IO) {
        failuresAsIo {
            val input = resolver.openInputStream(uri.toUri()) ?: throw IOException("No stream for $uri")
            input.use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size() > MAX_BYTES) throw IOException("Larger than a backup can be")
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }
    }

    /** Truncating write; a provider that does not know mode `wt` gets plain `w` (a picked new document is empty). */
    private fun openForWriting(uri: String): OutputStream {
        val parsed = uri.toUri()
        val stream = try {
            resolver.openOutputStream(parsed, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(parsed, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(parsed, "w")
        }
        return stream ?: throw IOException("No stream for $uri")
    }

    /** A revoked grant or a provider's own failure is just another unreadable or unwritable document to the caller. */
    private inline fun <T> failuresAsIo(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw IOException(e)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        /** Far above any real library's backup; a bigger pick is another kind of file. */
        internal const val MAX_BYTES = 32 * 1024 * 1024
    }
}
