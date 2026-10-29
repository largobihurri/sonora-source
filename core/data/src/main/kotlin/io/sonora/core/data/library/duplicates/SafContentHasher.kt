package io.sonora.core.data.library.duplicates

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.library.duplicates.ContentHasher
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [ContentHasher] over the files' documents: the bytes stream through the provider in [CHUNK_BYTES] chunks (a file is
 * never held in memory), on the IO dispatcher, and the read stops between chunks when the scan is cancelled.
 */
class SafContentHasher @Inject constructor(@ApplicationContext private val context: Context) : ContentHasher {

    override suspend fun hash(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri.toUri())?.use { sha256(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read a file to compare it", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to a file to compare it", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot open a file to compare it", e)
            null
        }
    }

    companion object {
        private const val TAG = "SonoraDuplicates"
        private const val CHUNK_BYTES = 64 * 1024

        /** SHA-256 of everything [input] holds, hex. */
        suspend fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
