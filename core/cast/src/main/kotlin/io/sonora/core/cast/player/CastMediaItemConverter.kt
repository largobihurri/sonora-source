package io.sonora.core.cast.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import io.sonora.core.cast.server.CastMime
import io.sonora.core.cast.server.CastQueueUrls

/**
 * Queue entries <-> Cast queue items. The phone plays SAF `content://` URIs, which a receiver cannot open, so each
 * entry is sent with the cast server's URLs for its audio and artwork ([urls], the current server session) and a
 * receiver-friendly type ([CastMime.audio]). The content id stays the entry's media id, which is how Media3's
 * RemoteCastPlayer maps the receiver's queue back to the original entries; an entry it does not know is read back
 * through [toMediaItem], which restores the file's URI so the phone can play it again.
 *
 * Without a server session (a cast being aborted) entries keep their own URIs: the receiver cannot play them, but the
 * player never fails on them.
 */
@OptIn(UnstableApi::class)
class CastMediaItemConverter(private val urls: () -> CastQueueUrls?) : MediaItemConverter {

    private val default = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = checkNotNull(mediaItem.localConfiguration) { "A queue entry without a URI" }
        val mapping = urls() ?: return default.toMediaQueueItem(mediaItem)
        val uri = local.uri.toString()
        val mimeType = CastMime.audio(local.mimeType, Uri.decode(uri))
        val key = mediaItem.mediaId.ifEmpty { uri }
        val mapped = mapping.map(key, uri, mimeType, mediaItem.mediaMetadata.artworkUri?.toString())
        val metadata = mediaItem.mediaMetadata.buildUpon()
            .setArtworkUri(mapped.artwork?.toUri())
            .setArtworkData(null, null)
            .build()
        val remote = mediaItem.buildUpon()
            .setUri(mapped.audio)
            .setMimeType(mimeType)
            .setMediaMetadata(metadata)
            .build()
        return default.toMediaQueueItem(remote)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val item = default.toMediaItem(mediaQueueItem)
        val url = item.localConfiguration?.uri?.toString() ?: return item
        val original = urls()?.originalAudioUri(url)?.toUri() ?: return item
        return item.buildUpon()
            .setUri(original)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(original).build())
            .build()
    }
}
