package io.sonora.core.playback

/**
 * MediaItem ids carry both the track id and the queue item id ("track:item"), so the queue can be read back from the
 * session with duplicates kept apart.
 */
internal object QueueMediaId {
    private const val SEPARATOR = ':'

    data class Parsed(val trackId: Long?, val itemId: Long?)

    fun format(trackId: Long, itemId: Long): String = "$trackId$SEPARATOR$itemId"

    fun parse(mediaId: String): Parsed = Parsed(
        trackId = mediaId.substringBefore(SEPARATOR).toLongOrNull(),
        itemId = mediaId.substringAfter(SEPARATOR, missingDelimiterValue = "").toLongOrNull(),
    )
}
