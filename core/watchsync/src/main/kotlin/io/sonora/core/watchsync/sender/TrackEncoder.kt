package io.sonora.core.watchsync.sender

import java.io.File

/** Turns a song into the file that is sent: the phone's Ogg Opus encoding. The Android adapter wraps the transcoder. */
interface TrackEncoder {
    /**
     * The encoded file of [candidate]. A finished encoding of the same song that is still kept (a transfer interrupted
     * after encoding) is returned as it is, not redone. [onProgress] gets the encoded fraction in `0..1`.
     *
     * @throws io.sonora.core.watchsync.TrackEncodingException when the song cannot be decoded or encoded.
     * @throws java.io.IOException when the file cannot be written.
     */
    suspend fun encode(candidate: SendCandidate, onProgress: (Float) -> Unit): File

    /** The watch has the song (or it was given up on): its encoded file is no longer needed. */
    fun release(candidate: SendCandidate)
}
