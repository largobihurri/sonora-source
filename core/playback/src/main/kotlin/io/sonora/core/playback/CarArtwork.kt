package io.sonora.core.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Artwork Android Auto can read. Library artwork is a `file://` picture in the app's artwork cache or a folder image
 * under the user's folder grant, and the car can open neither, so the car gets `content://<app>.artwork/<key>` served
 * by [ArtworkProvider]. Keys are opaque: `c_<name>` for a cache picture (its name is already a hash of its bytes), and
 * `s_<hash>` for a folder image, registered here when the car is shown it (in memory: after a restart it shows again
 * once the car browses there). Nothing else is ever served: no other file, no path, no other document.
 */
internal class CarArtwork(private val context: Context) {
    private val cache = File(context.cacheDir, CACHE_DIR)

    /** The car-readable URI for library artwork [artworkUri]; `null` when it is not artwork Sonora serves. */
    fun carUri(artworkUri: String): String? {
        val uri = artworkUri.toUri()
        val key = when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf { it.parentFile?.canonicalFile == cache.canonicalFile }
                ?.name?.takeIf(::isCacheName)?.let { CACHE_PREFIX + it }
            "content" -> (DOCUMENT_PREFIX + hash(artworkUri)).also { documents[it] = artworkUri }
            else -> null
        } ?: return null
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath(key).build().toString()
    }

    /** Opens the artwork [key] names, read-only; [FileNotFoundException] for anything else. */
    fun open(key: String): ParcelFileDescriptor = when {
        key.startsWith(CACHE_PREFIX) -> {
            val name = key.removePrefix(CACHE_PREFIX).takeIf(::isCacheName) ?: throw FileNotFoundException(key)
            ParcelFileDescriptor.open(File(cache, name), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        key.startsWith(DOCUMENT_PREFIX) -> {
            val document = documents[key] ?: throw FileNotFoundException(key)
            context.contentResolver.openFileDescriptor(document.toUri(), "r") ?: throw FileNotFoundException(key)
        }
        else -> throw FileNotFoundException(key)
    }

    /** A plain file name as the artwork cache writes them: no separators, no dot-only names. */
    private fun isCacheName(name: String): Boolean = NAME.matches(name) && name.any { it != '.' }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(HASH_CHARS)

    companion object {
        private const val CACHE_DIR = "artwork"
        private const val CACHE_PREFIX = "c_"
        private const val DOCUMENT_PREFIX = "s_"
        private const val HASH_CHARS = 32
        private val NAME = Regex("[A-Za-z0-9._-]+")

        /** Folder images shown to the car, by key; shared by the service and [ArtworkProvider] (same process). */
        private val documents = ConcurrentHashMap<String, String>()

        fun authority(context: Context) = "${context.packageName}.artwork"
    }
}

/** Serves [CarArtwork] to Android Auto: exported, read-only, opaque keys only (see [CarArtwork]). */
class ArtworkProvider : ContentProvider() {
    private lateinit var artwork: CarArtwork

    override fun onCreate(): Boolean {
        artwork = CarArtwork(context!!)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        return artwork.open(uri.pathSegments.singleOrNull() ?: throw FileNotFoundException(uri.toString()))
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
