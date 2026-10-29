package io.sonora.core.data.archive

import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ArchiveNaming
import io.sonora.core.domain.archive.ArchivePaths
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.ArchiveProgressListener
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.EntryVerdict
import io.sonora.core.domain.archive.ExtractedArchive
import io.sonora.core.domain.archive.ExtractedFile
import io.sonora.core.domain.archive.ManifestReading
import io.sonora.core.domain.archive.SkipReason
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.LocalFileHeader

/**
 * Reads a ZIP as a stream with zip4j, Sonora's archives and anyone else's (stored or deflated, plain, AES or
 * ZipCrypto), without copying it first: a shared file is read once, straight from its provider.
 *
 * Defensive by design: only music and a `sonora.json` are written, at paths [ArchivePaths] accepts (no zip slip);
 * a wrong password, a missing one and a damaged archive (not a ZIP, cut short, failing its CRC or AES check) end it
 * with their own [ArchiveFailure]; and a failed or cancelled extraction removes the folder it created.
 *
 * Blocking I/O: call it on an I/O dispatcher. Cancellation is checked between buffers.
 */
internal class ZipArchiveReader(private val bufferSize: Int = BUFFER_SIZE) {

    /**
     * Whether the archive needs a password, from its first file (folder entries skipped), and its manifest when that
     * file is a readable one (Sonora writes it first). An archive whose later entries alone are encrypted asks for the
     * password when extracted instead.
     */
    suspend fun inspect(input: InputStream): ArchiveSummary {
        var encrypted = false
        val zip = ZipInputStream(BufferedInputStream(input, bufferSize)) {
            encrypted = true
            null
        }
        val header = try {
            firstFile(zip)
        } catch (e: ArchiveFailureException) {
            if (encrypted || e.failure == ArchiveFailure.PasswordRequired) return ArchiveSummary(true, null)
            throw e
        } ?: throw ArchiveFailureException(ArchiveFailure.Damaged)
        if (header.isEncrypted) return ArchiveSummary(true, null)
        val manifest = if (ArchivePaths.classify(header.fileName) is EntryVerdict.Manifest) readManifest(zip, null) else null
        return ArchiveSummary(false, manifest)
    }

    /**
     * Extracts [input], the archive called [archiveName] of [archiveSize] bytes (`null` when unknown), into a new
     * folder made by [sink]: named after the manifest's folder for a Sonora archive, else after the archive file;
     * a leading folder of the same name is not repeated inside it. Fails with [ArchiveFailureException].
     */
    suspend fun extract(
        input: InputStream,
        archiveName: String,
        archiveSize: Long?,
        password: CharArray?,
        sink: ExtractionSink,
        onProgress: ArchiveProgressListener,
    ): ExtractedArchive {
        val secret = password?.takeIf { it.isNotEmpty() }
        val counting = CountingInputStream(BufferedInputStream(input, bufferSize))
        val zip = ZipInputStream(counting) { secret }
        val run = Extraction(zip, counting, archiveSize, secret, sink, onProgress)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val header = readingArchive(secret) { zip.nextEntry } ?: break
                run.entry(header, archiveName)
            }
            if (!run.sawEntry) throw ArchiveFailureException(ArchiveFailure.Damaged)
            if (run.files.isEmpty()) throw ArchiveFailureException(ArchiveFailure.NoMusic)
        } catch (e: Throwable) {
            if (run.rootUri != null) {
                // Also after a cancel: the sink's delete suspends, which a cancelled coroutine would refuse at once.
                withContext(NonCancellable) {
                    try {
                        sink.discard()
                    } catch (_: Exception) {
                        // Nothing more can be done for a folder the storage will not delete.
                    }
                }
            }
            throw e
        }
        return ExtractedArchive(checkNotNull(run.rootUri), run.files, run.manifest, run.skipped)
    }

    /** The state of one extraction, entry by entry. */
    private inner class Extraction(
        private val zip: ZipInputStream,
        private val counting: CountingInputStream,
        private val archiveSize: Long?,
        private val secret: CharArray?,
        private val sink: ExtractionSink,
        private val onProgress: ArchiveProgressListener,
    ) {
        var rootUri: String? = null
        var manifest: ArchiveManifest? = null
        var sawEntry = false
        val files = mutableListOf<ExtractedFile>()
        val skipped = mutableListOf<String>()
        private var leadingFolder: String? = null
        private var spaceChecked = false
        private var lastReported = 0L
        private val buffer = ByteArray(bufferSize)

        suspend fun entry(header: LocalFileHeader, archiveName: String) {
            sawEntry = true
            val path = header.fileName
            when (val verdict = ArchivePaths.classify(path)) {
                is EntryVerdict.Skipped -> if (verdict.reason != SkipReason.DIRECTORY) skipped += path
                is EntryVerdict.Manifest -> {
                    val bytes = readingArchive(secret) { zip.readAtMost(ArchiveManifest.MAX_BYTES) }
                    if (bytes == null) {
                        skipped += path
                        return
                    }
                    val reading = ArchiveManifestJson.decode(String(bytes, Charsets.UTF_8))
                    if (reading is ManifestReading.Read && manifest == null) {
                        manifest = reading.manifest
                        if (rootUri == null && verdict.segments.size == 2) leadingFolder = verdict.segments[0]
                    }
                    ensureRoot(archiveName)
                    write(verdict.segments, path) { out -> writing { out.write(bytes) } }
                }
                is EntryVerdict.Audio -> {
                    ensureRoot(archiveName)
                    val uri = write(verdict.segments, path) { out -> copy(out, path) }
                    files += ExtractedFile(path, uri)
                    report(path)
                }
            }
        }

        private suspend fun ensureRoot(archiveName: String) {
            if (rootUri != null) return
            val folder = leadingFolder ?: ArchiveNaming.folderName(archiveName.substringBeforeLast('.').ifEmpty { archiveName })
            leadingFolder = folder
            rootUri = writing { sink.createRoot(ArchiveNaming.folderName(folder)) }
        }

        /** Creates the file for [segments] (without the root's own folder) and fills it; returns its URI. */
        private suspend fun write(segments: List<String>, path: String, fill: suspend (java.io.OutputStream) -> Unit): String {
            val relative = if (segments.size >= 2 && segments[0].equals(leadingFolder, ignoreCase = true)) segments.drop(1) else segments
            val created = writing { sink.createFile(relative) }
            created.output.use { out ->
                if (!spaceChecked) {
                    spaceChecked = true
                    val available = sink.availableBytes()
                    if (archiveSize != null && available != null && available < archiveSize) {
                        throw ArchiveFailureException(ArchiveFailure.NotEnoughSpace(archiveSize, available))
                    }
                }
                fill(out)
            }
            return created.uri
        }

        private suspend fun copy(out: java.io.OutputStream, path: String) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = readingArchive(secret) { zip.read(buffer) }
                if (read < 0) break
                writing { out.write(buffer, 0, read) }
                if (counting.count - lastReported >= PROGRESS_STEP) report(path)
            }
        }

        private suspend fun report(path: String) {
            lastReported = counting.count
            val total = manifest?.entries?.size ?: 0
            onProgress(ArchiveProgress(files.size, total, counting.count, archiveSize ?: 0, path.substringAfterLast('/')))
        }

        /** Runs a write to the destination: out of space, or unwritable. */
        private inline fun <T> writing(write: () -> T): T = try {
            write()
        } catch (e: IOException) {
            val failure = if (isOutOfSpace(e)) ArchiveFailure.NotEnoughSpace(archiveSize ?: 0, 0) else ArchiveFailure.DestinationUnwritable
            throw ArchiveFailureException(failure, e)
        } catch (e: SecurityException) {
            throw ArchiveFailureException(ArchiveFailure.DestinationUnwritable, e)
        }
    }

    /**
     * The first entry that is a file, past the folder entries many programs write first (folders are never
     * encrypted, so they tell nothing about the password); `null` for an archive without one in its first
     * [MAX_LEADING_FOLDERS] entries.
     */
    private fun firstFile(zip: ZipInputStream): LocalFileHeader? {
        repeat(MAX_LEADING_FOLDERS) {
            val header = readingArchive(null) { zip.nextEntry } ?: return null
            if (!header.isDirectory) return header
        }
        return null
    }

    /** The manifest at the current entry, or `null` when it is too large or not one this version reads. */
    private fun readManifest(zip: ZipInputStream, secret: CharArray?): ArchiveManifest? {
        val bytes = readingArchive(secret) { zip.readAtMost(ArchiveManifest.MAX_BYTES) } ?: return null
        return (ArchiveManifestJson.decode(String(bytes, Charsets.UTF_8)) as? ManifestReading.Read)?.manifest
    }

    /**
     * Runs a read of the archive. zip4j reports a missing or wrong AES password, and a wrong ZipCrypto one, as
     * [ZipException.Type.WRONG_PASSWORD]; anything else that goes wrong reading (I/O, bad headers, checksums, or
     * zip4j choking on garbage) means a damaged archive.
     */
    private inline fun <T> readingArchive(secret: CharArray?, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArchiveFailureException) {
        throw e
    } catch (e: ZipException) {
        val failure = when {
            e.type != ZipException.Type.WRONG_PASSWORD -> ArchiveFailure.Damaged
            secret == null -> ArchiveFailure.PasswordRequired
            else -> ArchiveFailure.WrongPassword
        }
        throw ArchiveFailureException(failure, e)
    } catch (e: Exception) {
        throw ArchiveFailureException(ArchiveFailure.Damaged, e)
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_STEP = 1L shl 20
        const val MAX_LEADING_FOLDERS = 64
    }
}
