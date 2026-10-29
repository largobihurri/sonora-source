package io.sonora.core.cast.server

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.IOException

/**
 * Opens served files through [resolver]: the songs' SAF `content://` URIs with the app's persisted tree grant, and the
 * artwork's `content://` or `file://` URIs (the resolver opens both). A local document is a real file, so its length is
 * known and a seek is a channel move; a provider that hands out a pipe reports no length, and the server then streams
 * it whole. Artwork's type is read from its first bytes ([CastMime.image]).
 */
class ContentFileOpener(private val resolver: ContentResolver) : FileOpener {

    override fun open(served: Served): OpenedFile? {
        val uri = when (served) {
            is Served.Audio -> served.uri
            is Served.Artwork -> served.uri
        }.toUri()
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        val length = descriptor.statSize
        val seek: (Long) -> Unit = { offset ->
            try {
                input.channel.position(offset)
            } catch (_: IOException) {
                input.skip(offset) // a pipe cannot seek; the server never seeks a file of unknown length
            }
        }
        val mimeType = when (served) {
            is Served.Audio -> served.mimeType
            // A pipe could not rewind after sniffing; artwork is cached as real files, so this is only a fallback.
            is Served.Artwork -> if (length < 0) FALLBACK_IMAGE else {
                val head = ByteArray(IMAGE_HEAD_BYTES)
                val read = input.read(head).coerceAtLeast(0)
                seek(0)
                CastMime.image(head.copyOf(read))
            }
        }
        return OpenedFile(length, mimeType, input, seek)
    }

    private companion object {
        const val IMAGE_HEAD_BYTES = 12
        const val FALLBACK_IMAGE = "image/jpeg"
    }
}
