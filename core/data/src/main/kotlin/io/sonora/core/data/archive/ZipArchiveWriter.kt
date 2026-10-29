package io.sonora.core.data.archive

import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.ArchiveProgressListener
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/**
 * Writes a ZIP with zip4j: every entry stored (audio is compressed already; deflating it again costs time for no
 * gain) and, with a password, encrypted with WinZip AES-256 (AE-2: authenticated, no CRC leak), which 7-Zip, WinRAR,
 * Keka and most Android file managers open. Never ZipCrypto.
 *
 * Stored entries need their size before their data (it goes in the local header, where stream readers look for it),
 * so each source gives its exact size, and a source that turns out longer or shorter fails instead of writing an
 * entry other programs would misread.
 *
 * Blocking I/O: call it on an I/O dispatcher. Cancellation is checked between buffers.
 */
internal class ZipArchiveWriter(private val bufferSize: Int = BUFFER_SIZE) {

    /**
     * Writes [manifest] then [files] to [output] (closed at the end), encrypted with [password] when not `null` or
     * empty. Fails with [ArchiveFailureException]: [ArchiveFailure.UnreadableSource] for a source that cannot be read
     * whole, [ArchiveFailure.NotEnoughSpace] or [ArchiveFailure.DestinationUnwritable] when [output] fails.
     */
    suspend fun write(
        output: OutputStream,
        manifest: ArchiveSource,
        files: List<ArchiveSource>,
        password: CharArray?,
        onProgress: ArchiveProgressListener,
    ) {
        val encrypt = password != null && password.isNotEmpty()
        val bytesTotal = manifest.sizeBytes + files.sumOf { it.sizeBytes }
        var bytesDone = 0L
        var lastReported = 0L
        val buffer = ByteArray(bufferSize)
        val zip = writing(bytesTotal) { ZipOutputStream(BufferedOutputStream(output, bufferSize), password.takeIf { encrypt }) }
        try {
            (listOf(manifest) + files).forEachIndexed { index, source ->
                val entriesDone = (index - 1).coerceAtLeast(0)
                writing(bytesTotal) { zip.putNextEntry(parameters(source, encrypt)) }
                val input = reading(source) { source.open() }
                var copied = 0L
                input.use {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = reading(source) { input.read(buffer) }
                        if (read < 0) break
                        copied += read
                        if (copied > source.sizeBytes) throw ArchiveFailureException(ArchiveFailure.UnreadableSource(source.name))
                        writing(bytesTotal) { zip.write(buffer, 0, read) }
                        bytesDone += read
                        if (bytesDone - lastReported >= PROGRESS_STEP) {
                            lastReported = bytesDone
                            onProgress(ArchiveProgress(entriesDone, files.size, bytesDone, bytesTotal, source.name))
                        }
                    }
                }
                if (copied != source.sizeBytes) throw ArchiveFailureException(ArchiveFailure.UnreadableSource(source.name))
                writing(bytesTotal) { zip.closeEntry() }
                if (index > 0) onProgress(ArchiveProgress(index, files.size, bytesDone, bytesTotal, source.name))
            }
            writing(bytesTotal) { zip.close() }
        } catch (e: Throwable) {
            try {
                output.close()
            } catch (_: IOException) {
                // The archive is discarded anyway.
            }
            throw e
        }
    }

    private fun parameters(source: ArchiveSource, encrypt: Boolean) = ZipParameters().apply {
        fileNameInZip = source.path
        compressionMethod = CompressionMethod.STORE
        entrySize = source.sizeBytes
        if (encrypt) {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            aesVersion = AesVersion.TWO
        }
    }

    /** Runs a read of [source]: any failure but cancellation makes it unreadable. */
    private inline fun <T> reading(source: ArchiveSource, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArchiveFailureException) {
        throw e
    } catch (e: Exception) {
        throw ArchiveFailureException(ArchiveFailure.UnreadableSource(source.name), e)
    }

    /** Runs a write of the archive: out of space, or unwritable. */
    private inline fun <T> writing(bytesTotal: Long, write: () -> T): T = try {
        write()
    } catch (e: IOException) {
        val failure = if (isOutOfSpace(e)) ArchiveFailure.NotEnoughSpace(bytesTotal, 0) else ArchiveFailure.DestinationUnwritable
        throw ArchiveFailureException(failure, e)
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024

        /** Bytes between progress reports within an entry. */
        const val PROGRESS_STEP = 1L shl 20
    }
}

/** An [InputStream] read whole into memory, at most [maxBytes]; `null` when longer. */
internal fun InputStream.readAtMost(maxBytes: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) return null
    }
}
