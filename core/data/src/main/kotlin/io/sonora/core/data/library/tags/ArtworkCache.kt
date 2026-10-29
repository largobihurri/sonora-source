package io.sonora.core.data.library.tags

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded pictures extracted to `cacheDir/artwork`, named by a hash of their bytes so every track of an album
 * shares one file. The OS may clear the cache; the scanner re-reads files whose picture went missing.
 */
@Singleton
class ArtworkCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.cacheDir, "artwork")

    /** Stores [bytes] (once per distinct picture) and returns its `file://` URI. */
    fun store(bytes: ByteArray): String {
        directory.mkdirs()
        val file = File(directory, fileName(bytes))
        if (!file.exists()) {
            val partial = File(directory, "${file.name}.tmp")
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) partial.delete()
        }
        return file.toUri().toString()
    }

    fun isMissing(uri: String): Boolean = uri.toUri().path?.let { !File(it).exists() } ?: true

    /** Deletes cached pictures that no indexed track references any more. */
    fun prune(referenced: Set<String>) {
        val keep = referenced.mapNotNullTo(HashSet()) { it.toUri().lastPathSegment }
        directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun fileName(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(HASH_CHARS)
        return "$hash.${extension(bytes)}"
    }

    private fun extension(bytes: ByteArray): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val HASH_CHARS = 32
    }
}
