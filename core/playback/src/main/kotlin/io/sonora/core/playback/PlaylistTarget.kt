package io.sonora.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.queue.QueueEdit

/** The playlist operations queue edits need, kept apart from Media3 so the edit mapping is JVM-testable. */
internal interface PlaylistTarget {
    fun setAll(items: List<QueueItem>, startIndex: Int)
    fun insert(index: Int, items: List<QueueItem>)
    fun move(from: Int, to: Int)
    fun remove(from: Int, toExclusive: Int)
    fun replace(from: Int, toExclusive: Int, items: List<QueueItem>)
    fun skipTo(index: Int)
    fun update(index: Int, item: QueueItem)
}

/** Applies queue edits in order. */
internal fun List<QueueEdit>.applyTo(target: PlaylistTarget) = forEach { edit ->
    when (edit) {
        is QueueEdit.SetAll -> target.setAll(edit.items, edit.startIndex)
        is QueueEdit.Insert -> target.insert(edit.index, edit.items)
        is QueueEdit.Move -> target.move(edit.from, edit.to)
        is QueueEdit.Remove -> target.remove(edit.from, edit.toExclusive)
        is QueueEdit.Replace -> target.replace(edit.from, edit.toExclusive, edit.items)
        is QueueEdit.SkipTo -> target.skipTo(edit.index)
        is QueueEdit.Update -> target.update(edit.index, edit.item)
    }
}

/**
 * [PlaylistTarget] on a Media3 [Player] (the app's MediaController). Media3 keeps the current item playing through
 * inserts, moves and changes to other ranges, so only [setAll] and [skipTo] change what plays. [update] swaps an item
 * for the same file with new details; ExoPlayer takes the new metadata without re-preparing it.
 */
internal class PlayerPlaylist(
    private val player: Player,
    private val toMediaItem: (QueueItem) -> MediaItem,
) : PlaylistTarget {
    override fun setAll(items: List<QueueItem>, startIndex: Int) {
        player.setMediaItems(items.map(toMediaItem), startIndex, 0L)
        player.prepare()
    }

    override fun insert(index: Int, items: List<QueueItem>) = player.addMediaItems(index, items.map(toMediaItem))

    override fun move(from: Int, to: Int) = player.moveMediaItem(from, to)

    override fun remove(from: Int, toExclusive: Int) = player.removeMediaItems(from, toExclusive)

    override fun replace(from: Int, toExclusive: Int, items: List<QueueItem>) = when {
        items.isEmpty() -> if (from < toExclusive) player.removeMediaItems(from, toExclusive) else Unit
        from == toExclusive -> player.addMediaItems(from, items.map(toMediaItem))
        else -> player.replaceMediaItems(from, toExclusive, items.map(toMediaItem))
    }

    override fun skipTo(index: Int) = player.seekTo(index, 0L)

    override fun update(index: Int, item: QueueItem) = player.replaceMediaItem(index, toMediaItem(item))
}
