package io.sonora.core.data.library.edit

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.AudioFixtures
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.tags.TagChanges
import io.sonora.core.domain.tags.TagField
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The write-verify-replace flow against an in-memory document and the real codec (or a faulty one). */
class SafeTagRewriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val workDir by lazy { folder.newFolder("work") }
    private val original = AudioFixtures.flac()
    private val retitle = TagChanges(mapOf(TagField.TITLE to "New"))

    /** A document held in memory; [failOpen] refuses to open it, [failAfter] breaks writes after that many bytes. */
    private class MemoryDocument(var bytes: ByteArray) : TagDocument {
        var failOpen: Exception? = null
        var failAfter: Int? = null
        var opens = 0

        override fun size(): Long = bytes.size.toLong()

        override fun copyTo(out: OutputStream) = out.write(bytes)

        override fun truncateAndOpen(): OutputStream {
            failOpen?.let { throw it }
            opens++
            bytes = ByteArray(0)
            val limit = failAfter
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (limit != null && size() + len > limit) {
                        bytes = toByteArray() + b.copyOfRange(off, off + (limit - size()).coerceAtLeast(0))
                        throw IOException("disk went away")
                    }
                    super.write(b, off, len)
                    bytes = toByteArray()
                }
            }
        }
    }

    /**
     * A document held in a real file and written through a real descriptor. [reportedSize] stands in for a size the
     * provider cached before the save; [ignoreTruncationOnce] makes the first open leave 1 KB of old bytes past the
     * new end, as a provider that ignores the `t` of `"rwt"`.
     */
    private class FileDocument(val file: File) : TagDocument {
        var reportedSize: Long? = null
        var ignoreTruncationOnce = false

        override fun size(): Long = reportedSize ?: file.length()

        override fun copyTo(out: OutputStream) {
            file.inputStream().use { it.copyTo(out) }
        }

        override fun truncateAndOpen(): OutputStream {
            if (!ignoreTruncationOnce) return FileOutputStream(file)
            ignoreTruncationOnce = false
            val untruncated = RandomAccessFile(file, "rw").apply { setLength(length() + 1024) }
            return FileOutputStream(untruncated.fd)
        }
    }

    private fun rewrite(document: TagDocument, codec: TagCodec = JAudioTaggerTagCodec, changes: TagChanges = retitle) =
        SafeTagRewrite(workDir, codec).rewrite(document, "flac", changes)

    private fun titleOf(bytes: ByteArray): String? {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return JAudioTaggerTagCodec.read(file, "flac").tags.fields[TagField.TITLE]
    }

    @Test
    fun `the document gets the verified copy and no working files are left`() {
        val document = MemoryDocument(original)

        val result = rewrite(document)

        assertThat((result as RewriteResult.Done).written.tags.fields[TagField.TITLE]).isEqualTo("New")
        assertThat(titleOf(document.bytes)).isEqualTo("New")
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `a copy that does not verify leaves the document untouched`() {
        val document = MemoryDocument(original)
        val writesNothing = object : TagCodec by JAudioTaggerTagCodec {
            override fun write(file: File, readerKey: String, changes: TagChanges) = Unit
        }

        assertThat(rewrite(document, writesNothing)).isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_VERIFIED))
        assertThat(document.bytes).isEqualTo(original)
        assertThat(document.opens).isEqualTo(0)
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `a write the format refuses leaves the document untouched`() {
        val document = MemoryDocument(original)
        val refuses = object : TagCodec by JAudioTaggerTagCodec {
            override fun write(file: File, readerKey: String, changes: TagChanges) = throw IOException("refused")
        }

        assertThat(rewrite(document, refuses)).isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_SAVED))
        assertThat(document.bytes).isEqualTo(original)
    }

    @Test
    fun `a file that does not parse is unreadable and untouched`() {
        val junk = ByteArray(64) { 7 }
        val document = MemoryDocument(junk)

        assertThat(rewrite(document)).isEqualTo(RewriteResult.Failed(TagEditFailure.UNREADABLE))
        assertThat(document.bytes).isEqualTo(junk)
    }

    @Test
    fun `a document that refuses writing is reported read-only and untouched`() {
        val document = MemoryDocument(original).apply { failOpen = SecurityException("read-only grant") }

        assertThat(rewrite(document)).isEqualTo(RewriteResult.Failed(TagEditFailure.READ_ONLY_LOCATION))
        assertThat(document.bytes).isEqualTo(original)
    }

    @Test
    fun `a replace that breaks part way puts the original back`() {
        val document = MemoryDocument(original).apply { failAfter = 100 }
        var opened = 0
        val breaksOnce = object : TagDocument by document {
            override fun truncateAndOpen(): OutputStream {
                if (++opened == 2) document.failAfter = null // the restore goes through
                return document.truncateAndOpen()
            }
        }

        assertThat(rewrite(breaksOnce)).isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_SAVED))
        assertThat(document.bytes).isEqualTo(original)
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `a replace whose restore also breaks is reported as interrupted`() {
        val document = MemoryDocument(original).apply { failAfter = 100 }

        assertThat(rewrite(document)).isEqualTo(RewriteResult.Failed(TagEditFailure.INTERRUPTED))
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `too little free space fails before anything is copied`() {
        val document = object : TagDocument by MemoryDocument(original) {
            override fun size(): Long = Long.MAX_VALUE / 4
        }

        assertThat(rewrite(document)).isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_ENOUGH_SPACE))
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `a stale size from the provider does not fail a good write`() {
        val document = FileDocument(folder.newFile("song.flac").apply { writeBytes(original) }).apply {
            reportedSize = original.size + 4096L // cached before the save, never refreshed
        }

        val result = rewrite(document)

        assertThat((result as RewriteResult.Done).written.tags.fields[TagField.TITLE]).isEqualTo("New")
        assertThat(titleOf(document.file.readBytes())).isEqualTo("New")
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `old bytes left past the new end are caught and the original goes back`() {
        val document = FileDocument(folder.newFile("song.flac").apply { writeBytes(original) }).apply {
            ignoreTruncationOnce = true
        }

        assertThat(rewrite(document)).isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_SAVED))
        assertThat(document.file.readBytes()).isEqualTo(original)
        assertThat(workDir.listFiles()).isEmpty()
    }

    @Test
    fun `working files that cannot be created fail the save without throwing or touching the document`() {
        val document = MemoryDocument(original)
        // A plain file where the work directory should be: no temporary file can go in it, whoever runs the test
        // (a read-only directory would not stop root, as in CI containers).
        val notADirectory = folder.newFile("not-a-directory")

        assertThat(SafeTagRewrite(notADirectory, JAudioTaggerTagCodec).rewrite(document, "flac", retitle))
            .isEqualTo(RewriteResult.Failed(TagEditFailure.NOT_SAVED))
        assertThat(document.bytes).isEqualTo(original)
        assertThat(notADirectory.isFile).isTrue()
        assertThat(folder.root.list()).asList().containsExactly("not-a-directory")
    }
}
