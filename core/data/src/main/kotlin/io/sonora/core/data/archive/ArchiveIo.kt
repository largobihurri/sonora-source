package io.sonora.core.data.archive

import io.sonora.core.domain.archive.ArchiveFailure
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Ends an archive operation with [failure]; turned into `ArchiveOutcome.Failed` at the port. */
internal class ArchiveFailureException(val failure: ArchiveFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/** One file to pack: its [path] inside the archive, its exact size, the [name] failures show, and how to read it. */
internal class ArchiveSource(val path: String, val sizeBytes: Long, val name: String, val open: () -> InputStream)

/** A new file of an extraction: its URI and the stream that fills it. */
internal class CreatedFile(val uri: String, val output: OutputStream)

/** Where an extraction writes: one new folder in the destination, with files (and folders) below it. */
internal interface ExtractionSink {
    /** Creates the extraction folder, called [name] or a free variant of it; returns its URI. Called once, first. */
    suspend fun createRoot(name: String): String

    /** Free bytes where the files go, `null` when the storage does not tell. Asked after the first file exists. */
    suspend fun availableBytes(): Long?

    /** A new file at [segments] (folders, then the file name) below the extraction folder. */
    suspend fun createFile(segments: List<String>): CreatedFile

    /** Removes the extraction folder and everything written to it (a failed or cancelled extraction). */
    suspend fun discard()
}

/** Counts the bytes read through it: the position in an archive read as a stream. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }

    override fun markSupported(): Boolean = false
}

/** Whether [e] is the storage running out of space (ENOSPC), as Linux and Android word it. */
internal fun isOutOfSpace(e: IOException): Boolean {
    val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return "ENOSPC" in message || "No space left" in message
}
