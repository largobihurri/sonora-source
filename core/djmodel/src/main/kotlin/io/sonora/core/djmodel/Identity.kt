package io.sonora.core.djmodel

import java.io.Closeable

/**
 * Random-access, read-only bytes of one audio file, whatever holds them (a file, a SAF document, a test array).
 * The caller opens and closes it; an identifier or analyser only reads.
 */
interface ByteSource : Closeable {

    /** Size of the file in bytes. */
    val length: Long

    /**
     * Reads up to [count] bytes at [position] into [buffer] from [offset]; returns the number read (at least 1 while
     * [position] is inside the file), or -1 at or after the end.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
}

/** Computes the [AudioId] of a file. Pure of the library: the same audio gives the same id wherever it is stored. */
interface AudioIdentifier {

    /** @throws java.io.IOException when the bytes cannot be read. Cancellation is never reported as a failure. */
    suspend fun identify(source: ByteSource): AudioId
}

/** What tells a file's bytes may have changed: its size and its last-modified time, as the file system reports them. */
data class FileStamp(val sizeBytes: Long, val lastModifiedMs: Long)

/**
 * A permanent uri to audio-id memo, so a known file is not hashed again. It is only a cache of a value that can always
 * be recomputed from the bytes: a wrong or missing entry costs a hash, never a wrong analysis.
 */
interface AudioIdIndex {

    /**
     * The id remembered for [sourceUri], or `null` when it was never identified.
     *
     * When [stamp] is given the memo only counts if it was remembered with that same stamp: a file whose size or
     * last-modified time changed since (or that was remembered without a stamp) is a miss, so the caller hashes it again.
     * Without a [stamp] the remembered id is returned as is.
     */
    suspend fun idFor(sourceUri: String, stamp: FileStamp? = null): AudioId?

    /** Remembers that [sourceUri] holds the audio [id], as found when the file looked like [stamp]. */
    suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp? = null)
}
