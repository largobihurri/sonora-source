package io.sonora.core.playback

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Loads the saved [PlaybackSnapshot] in a state that is safe to play: entries whose folder grant is gone are dropped
 * (with the filter [accessFilter] makes for this load, cheap per entry), and the entry about to play is checked to still
 * exist ([exists], one provider call each), moving on to the next entry while it does not. Never throws: anything
 * unreadable resumes nothing.
 */
internal class PlaybackResumption(
    private val store: PlaybackSnapshotStore,
    private val accessFilter: () -> (Track) -> Boolean,
    private val exists: (Track) -> Boolean,
    private val io: CoroutineDispatcher,
) {
    suspend fun load(): PlaybackSnapshot? = try {
        withContext(io) {
            val isAccessible = accessFilter()
            var snapshot = store.load()?.restorable { isAccessible(it.track) }
            while (snapshot != null) {
                val current = snapshot.items[snapshot.currentIndex]
                if (exists(current.track)) break
                snapshot = snapshot.restorable { it.id != current.id }
            }
            snapshot
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Saved queue not resumed", e)
        null
    }

    companion object {
        private const val TAG = "SonoraResumption"

        /** The snapshot as the session's playlist, starting at its current entry and position. */
        @OptIn(UnstableApi::class)
        fun mediaItems(snapshot: PlaybackSnapshot) = MediaSession.MediaItemsWithStartPosition(
            snapshot.items.map(MediaItemMapper::toMediaItem),
            snapshot.currentIndex,
            snapshot.positionMs,
        )
    }
}

/** Whether a queued file can still be read: the folder grant is held and the document still exists. */
internal class SafTrackAccess(context: Context) {
    private val resolver = context.contentResolver

    /** Folder grants are read once per check of a whole queue, not per entry. */
    fun accessibleFilter(): (Track) -> Boolean {
        val trees = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }
        return { hasGrant(it.uri, trees) }
    }

    fun exists(track: Track): Boolean = try {
        resolver.query(track.uri.toUri(), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        // SecurityException without a grant, IllegalArgumentException / FileNotFoundException for a deleted document.
        false
    }

    companion object {
        /**
         * A document of a picked folder (`.../tree/<folder>/document/<file>`) needs that folder's grant; any other URI
         * is not governed by folder grants and is left to the existence check.
         */
        fun hasGrant(uri: String, grantedTrees: List<String>): Boolean {
            if (!uri.contains("/tree/") || !uri.contains("/document/")) return true
            val tree = uri.substringBefore("/document/")
            return tree in grantedTrees
        }
    }
}
