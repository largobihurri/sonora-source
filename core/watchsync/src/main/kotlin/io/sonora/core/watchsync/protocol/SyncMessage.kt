package io.sonora.core.watchsync.protocol

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.SyncLimits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why the watch turned a song down, or lost it on the way. */
@Serializable
enum class SyncError {
    /** Not enough free space (after the reserve) for the rest of the song. */
    NO_SPACE,

    /** The offer is malformed (id, size, checksum). */
    INVALID_TRACK,

    /** The bytes received do not hash to the offered checksum; the partial file was dropped. */
    CHECKSUM_MISMATCH,

    /** The watch could not write its storage. */
    STORAGE_ERROR,

    /** The phone could not decode or encode the song. Only ever in the phone's own report, never sent. */
    ENCODING_FAILED,
}

/**
 * What the phone and the watch say to each other over one channel, as JSON control frames between data frames.
 *
 * A session: the phone sends [Hello] and gets the watch's [Hello]; [Query] gets [FreeSpace] and [Contents] (one or more pages); for each song
 * the phone sends [Offer] and gets [Have] (nothing to do), [Reject], or [Request] with the byte to start from, then
 * streams data frames while the watch answers with [Progress] and finally [Done] or [Failed]; [Remove] gets [Removed];
 * [End] says no more messages follow.
 */
@Serializable
sealed interface SyncMessage {

    /** The first message each side sends. Versions that differ cannot talk. */
    @Serializable
    @SerialName("hello")
    data class Hello(val version: Int = SyncLimits.PROTOCOL_VERSION) : SyncMessage

    /** Phone to watch: how much room is there, and what is on the watch? */
    @Serializable
    @SerialName("query")
    data object Query : SyncMessage

    /** Watch to phone. Songs may use up to [usableBytes]; the rest is the watch's own reserve. */
    @Serializable
    @SerialName("free_space")
    data class FreeSpace(
        val freeBytes: Long,
        val totalBytes: Long,
        val reserveBytes: Long = SyncLimits.MIN_FREE_RESERVE_BYTES,
    ) : SyncMessage {
        val usableBytes: Long get() = maxOf(0L, freeBytes - reserveBytes)
    }

    /**
     * Watch to phone: the songs the watch holds in full, with their tags, sizes and checksums. A big library comes in
     * several pages (see [ContentsPaging]): every page but the last has [more] set, and the phone joins them in order.
     * A peer that predates pages never sets [more], so its one message is a complete list; a peer that predates pages
     * reading a paged list ignores the flag and sees only the first page.
     */
    @Serializable
    @SerialName("contents")
    data class Contents(val tracks: List<ManifestTrack>, val more: Boolean = false) : SyncMessage

    /** Phone to watch: a finished encoding is ready to send. */
    @Serializable
    @SerialName("offer")
    data class Offer(val track: ManifestTrack) : SyncMessage

    /** Watch to phone: that exact song (same id and checksum) is already here. */
    @Serializable
    @SerialName("have")
    data class Have(val trackId: String) : SyncMessage

    /** Watch to phone: send the song's bytes starting at [offset] (a transfer cut short earlier resumes there). */
    @Serializable
    @SerialName("request")
    data class Request(val trackId: String, val offset: Long) : SyncMessage

    /** Watch to phone: the offer is refused; [trackId] is `null` for a refusal of the whole session. */
    @Serializable
    @SerialName("reject")
    data class Reject(val trackId: String?, val reason: SyncError) : SyncMessage

    /** Watch to phone: this many bytes of the song are on the watch's disk. */
    @Serializable
    @SerialName("progress")
    data class Progress(val trackId: String, val receivedBytes: Long) : SyncMessage

    /** Watch to phone: the song arrived, matched its checksum and is in the library. */
    @Serializable
    @SerialName("done")
    data class Done(val trackId: String) : SyncMessage

    /** Watch to phone: the song did not make it in. */
    @Serializable
    @SerialName("failed")
    data class Failed(val trackId: String, val reason: SyncError) : SyncMessage

    /** Phone to watch: delete these songs. */
    @Serializable
    @SerialName("remove")
    data class Remove(val trackIds: List<String>) : SyncMessage

    /** Watch to phone: these songs are gone (asking for one that is already gone also succeeds). */
    @Serializable
    @SerialName("removed")
    data class Removed(val trackIds: List<String>) : SyncMessage

    /** Phone to watch: nothing more is coming. */
    @Serializable
    @SerialName("end")
    data object End : SyncMessage
}
