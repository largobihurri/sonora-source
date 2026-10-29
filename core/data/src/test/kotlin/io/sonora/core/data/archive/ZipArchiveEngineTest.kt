package io.sonora.core.data.archive

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ArchiveProgress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The ZIP engine on plain files: what it writes, what it reads back, and what it refuses. */
class ZipArchiveEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = ZipArchiveWriter()
    private val reader = ZipArchiveReader()

    private val manifest = ArchiveManifest(
        ArchiveKind.ALBUM,
        "Record",
        listOf(
            ArchiveEntry("01 - Band - Intro.flac", "Intro", "Band", "Record", trackNumber = 1),
            ArchiveEntry("02 - Band - Ñandú.mp3", "Ñandú", "Band", "Record", trackNumber = 2),
        ),
    )
    private val songs = listOf(Random(1).nextBytes(300_000), Random(2).nextBytes(123_457))

    /** Extraction into a plain folder that also refuses, as a second line of defence, any path leaving it. */
    private class FolderSink(private val destination: File, var available: Long? = null) : ExtractionSink {
        var root: File? = null
        var discarded = false

        override suspend fun createRoot(name: String): String {
            val folder = File(destination, name)
            check(folder.mkdirs())
            root = folder
            return folder.toURI().toString()
        }

        override suspend fun availableBytes(): Long? = available

        override suspend fun createFile(segments: List<String>): CreatedFile {
            val base = checkNotNull(root).canonicalFile
            val file = File(base, segments.joinToString(File.separator)).canonicalFile
            check(file.path.startsWith(base.path + File.separator)) { "outside: $file" }
            file.parentFile.mkdirs()
            return CreatedFile(file.toURI().toString(), FileOutputStream(file))
        }

        override suspend fun discard() {
            discarded = true
            root?.deleteRecursively()
        }
    }

    private fun sources(): List<ArchiveSource> = songs.mapIndexed { index, bytes ->
        val file = manifest.entries[index].file
        ArchiveSource("Record/$file", bytes.size.toLong(), file) { ByteArrayInputStream(bytes) }
    }

    private fun manifestSource(): ArchiveSource {
        val bytes = ArchiveManifestJson.encode(manifest).toByteArray()
        return ArchiveSource("Record/sonora.json", bytes.size.toLong(), "sonora.json") { ByteArrayInputStream(bytes) }
    }

    private fun archive(password: String? = null, name: String = "Record.zip"): File {
        val file = File(temp.root, name)
        runBlocking { FileOutputStream(file).use { writer.write(it, manifestSource(), sources(), password?.toCharArray()) {} } }
        return file
    }

    private fun extract(archive: File, password: String? = null, sink: FolderSink = FolderSink(temp.newFolder())) = runBlocking {
        FileInputStream(archive).use { reader.extract(it, archive.name, archive.length(), password?.toCharArray(), sink) {} }
    }

    private fun failureOf(block: () -> Unit): ArchiveFailure = assertThrows(ArchiveFailureException::class.java, block).failure

    /** An archive with the raw [names] (each holding a few bytes), as a hostile or careless program could make. */
    private fun rawArchive(vararg names: String): File {
        val file = File(temp.root, "raw.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipParameters().apply { fileNameInZip = name })
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an archive without a password round-trips, manifest first`() {
        val zip = archive()
        val sink = FolderSink(temp.newFolder())
        val extracted = extract(zip, sink = sink)

        val root = checkNotNull(sink.root)
        assertThat(root.name).isEqualTo("Record")
        assertThat(File(root, "01 - Band - Intro.flac").readBytes()).isEqualTo(songs[0])
        assertThat(File(root, "02 - Band - Ñandú.mp3").readBytes()).isEqualTo(songs[1])
        assertThat(File(root, "sonora.json").isFile).isTrue()
        assertThat(extracted.manifest).isEqualTo(manifest)
        assertThat(extracted.files.map { it.path })
            .containsExactly("Record/01 - Band - Intro.flac", "Record/02 - Band - Ñandú.mp3").inOrder()
        assertThat(extracted.skippedEntries).isEmpty()
        assertThat(ZipFile(zip).fileHeaders.first().fileName).isEqualTo("Record/sonora.json")
    }

    @Test
    fun `entries are stored, and java's own zip reader opens a plain archive`() {
        val zip = archive()
        java.util.zip.ZipFile(zip).use { jdk ->
            val entry = jdk.getEntry("Record/01 - Band - Intro.flac")
            assertThat(entry.method).isEqualTo(java.util.zip.ZipEntry.STORED)
            assertThat(jdk.getInputStream(entry).readBytes()).isEqualTo(songs[0])
        }
        assertThat(zip.length()).isAtLeast(songs.sumOf { it.size }.toLong())
    }

    @Test
    fun `with a password every entry is AES-256 (AE-2) over stored data, as zip4j's own reader sees it`() {
        val zip = archive(password = "Correct-Horse-42")
        val headers = ZipFile(zip).fileHeaders
        assertThat(headers).hasSize(3)
        for (header in headers) {
            assertThat(header.isEncrypted).isTrue()
            assertThat(header.encryptionMethod).isEqualTo(EncryptionMethod.AES)
            assertThat(header.aesExtraDataRecord.aesKeyStrength).isEqualTo(AesKeyStrength.KEY_STRENGTH_256)
            assertThat(header.aesExtraDataRecord.aesVersion).isEqualTo(AesVersion.TWO)
            assertThat(header.aesExtraDataRecord.compressionMethod).isEqualTo(CompressionMethod.STORE)
        }
        val out = temp.newFolder()
        ZipFile(zip, "Correct-Horse-42".toCharArray()).extractAll(out.path)
        assertThat(File(out, "Record/02 - Band - Ñandú.mp3").readBytes()).isEqualTo(songs[1])
    }

    @Test
    fun `an encrypted archive round-trips with its password`() {
        val zip = archive(password = "Correct-Horse-42")
        val sink = FolderSink(temp.newFolder())
        val extracted = extract(zip, "Correct-Horse-42", sink)
        assertThat(File(checkNotNull(sink.root), "01 - Band - Intro.flac").readBytes()).isEqualTo(songs[0])
        assertThat(extracted.manifest).isEqualTo(manifest)
    }

    @Test
    fun `a wrong password and a missing one are told apart, and nothing is left behind`() {
        val zip = archive(password = "Correct-Horse-42")
        val wrong = FolderSink(temp.newFolder())
        assertThat(failureOf { extract(zip, "wrong-password-1", wrong) }).isEqualTo(ArchiveFailure.WrongPassword)
        assertThat(failureOf { extract(zip, null) }).isEqualTo(ArchiveFailure.PasswordRequired)
        assertThat(wrong.root?.exists() ?: false).isFalse()
    }

    @Test
    fun `inspecting tells an encrypted archive and reads a plain one's manifest`() {
        val plain = archive(name = "plain.zip")
        val locked = archive(password = "Correct-Horse-42", name = "locked.zip")
        runBlocking {
            val open = FileInputStream(plain).use { reader.inspect(it) }
            assertThat(open.encrypted).isFalse()
            assertThat(open.manifest).isEqualTo(manifest)
            val closed = FileInputStream(locked).use { reader.inspect(it) }
            assertThat(closed.encrypted).isTrue()
            assertThat(closed.manifest).isNull()
        }
    }

    @Test
    fun `a truncated archive is damaged and its partial folder removed`() {
        for (password in listOf(null, "Correct-Horse-42")) {
            val zip = archive(password, name = "full-$password.zip")
            val cut = File(temp.root, "cut-$password.zip")
            cut.writeBytes(zip.readBytes().copyOf((zip.length() * 0.6).toInt()))
            val sink = FolderSink(temp.newFolder())
            assertThat(failureOf { extract(cut, password, sink) }).isEqualTo(ArchiveFailure.Damaged)
            assertThat(sink.discarded).isTrue()
            assertThat(sink.root?.exists() ?: false).isFalse()
        }
    }

    @Test
    fun `a cancelled extraction still removes its partial folder through a suspending sink`() {
        val zip = archive()
        // Like SafExtractionSink, whose delete switches dispatcher: that throws at once in a cancelled coroutine.
        val sink = object : ExtractionSink {
            val inner = FolderSink(temp.newFolder())
            override suspend fun createRoot(name: String) = inner.createRoot(name)
            override suspend fun availableBytes() = inner.availableBytes()
            override suspend fun createFile(segments: List<String>) = inner.createFile(segments)
            override suspend fun discard() = withContext(Dispatchers.IO) { inner.discard() }
        }
        runBlocking {
            val job = launch(Dispatchers.IO) {
                FileInputStream(zip).use {
                    reader.extract(it, zip.name, zip.length(), null, sink) { progress ->
                        if (progress.entriesDone >= 1) this@launch.cancel()
                    }
                }
            }
            job.join()
            assertThat(job.isCancelled).isTrue()
        }
        assertThat(sink.inner.discarded).isTrue()
        assertThat(sink.inner.root?.exists() ?: false).isFalse()
    }

    @Test
    fun `inspecting looks past leading folder entries for encryption and the manifest`() {
        fun withFolderFirst(password: String?, name: String): File {
            val file = File(temp.root, name)
            val params = { entry: String ->
                ZipParameters().apply {
                    fileNameInZip = entry
                    if (password != null && !entry.endsWith('/')) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
            }
            ZipOutputStream(FileOutputStream(file), password?.toCharArray()).use { zip ->
                zip.putNextEntry(params("Record/"))
                zip.closeEntry()
                zip.putNextEntry(params("Record/sonora.json"))
                zip.write(ArchiveManifestJson.encode(manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(params("Record/01 - Band - Intro.flac"))
                zip.write(songs[0])
                zip.closeEntry()
            }
            return file
        }
        runBlocking {
            val locked = FileInputStream(withFolderFirst("Correct-Horse-42", "locked-dir.zip")).use { reader.inspect(it) }
            assertThat(locked.encrypted).isTrue()
            val plain = FileInputStream(withFolderFirst(null, "plain-dir.zip")).use { reader.inspect(it) }
            assertThat(plain.encrypted).isFalse()
            assertThat(plain.manifest).isEqualTo(manifest)
        }
    }

    @Test
    fun `a file that is not a zip is damaged`() {
        val text = File(temp.root, "notes.zip").apply { writeText("just some text, not an archive") }
        assertThat(failureOf { extract(text) }).isEqualTo(ArchiveFailure.Damaged)
    }

    @Test
    fun `entries reaching outside the destination are skipped and nothing lands outside`() {
        val zip = rawArchive("../evil.mp3", "/abs.mp3", "a/../../b.mp3", "C:\\x.mp3", "..\\..\\win.mp3", "Mix/ok.mp3", "Mix/notes.txt")
        val parent = temp.newFolder()
        val destination = File(parent, "dest").apply { mkdirs() }
        val sink = FolderSink(destination)
        val extracted = runBlocking { FileInputStream(zip).use { reader.extract(it, "Mix.zip", zip.length(), null, sink) {} } }

        assertThat(extracted.files.map { it.path }).containsExactly("Mix/ok.mp3")
        assertThat(extracted.skippedEntries)
            .containsExactly("../evil.mp3", "/abs.mp3", "a/../../b.mp3", "C:\\x.mp3", "..\\..\\win.mp3", "Mix/notes.txt")
        val written = parent.walkTopDown().filter { it.isFile }.map { it.relativeTo(parent).path }.toList()
        assertThat(written).containsExactly("dest/Mix/ok.mp3".replace('/', File.separatorChar))
        assertThat(extracted.manifest).isNull()
    }

    @Test
    fun `an archive without music is reported as such`() {
        val zip = rawArchive("readme.txt", "cover.jpg")
        assertThat(failureOf { extract(zip) }).isEqualTo(ArchiveFailure.NoMusic)
    }

    @Test
    fun `extraction stops before writing when the destination lacks space`() {
        val zip = archive()
        val sink = FolderSink(temp.newFolder(), available = 1_000)
        val failure = failureOf { extract(zip, sink = sink) }
        assertThat(failure).isEqualTo(ArchiveFailure.NotEnoughSpace(zip.length(), 1_000))
        assertThat(sink.root?.exists() ?: false).isFalse()
    }

    @Test
    fun `a song that cannot be read fails the compression with its name`() {
        val broken = ArchiveSource("Record/gone.flac", 10, "gone.flac") { throw IOException("No such file") }
        val failure = failureOf {
            runBlocking { writer.write(OutputStream.nullOutputStream(), manifestSource(), listOf(broken), null) {} }
        }
        assertThat(failure).isEqualTo(ArchiveFailure.UnreadableSource("gone.flac"))
    }

    @Test
    fun `a song whose size changed while packing fails rather than writing a broken entry`() {
        val shrunk = ArchiveSource("Record/a.flac", 100, "a.flac") { ByteArrayInputStream(ByteArray(60)) }
        val failure = failureOf {
            runBlocking { writer.write(OutputStream.nullOutputStream(), manifestSource(), listOf(shrunk), null) {} }
        }
        assertThat(failure).isEqualTo(ArchiveFailure.UnreadableSource("a.flac"))
    }

    @Test
    fun `progress counts entries and bytes up to the total`() {
        val reports = mutableListOf<ArchiveProgress>()
        runBlocking { writer.write(OutputStream.nullOutputStream(), manifestSource(), sources(), null) { reports += it } }
        val last = reports.last()
        assertThat(last.entriesDone).isEqualTo(2)
        assertThat(last.entriesTotal).isEqualTo(2)
        assertThat(last.bytesDone).isEqualTo(last.bytesTotal)
        assertThat(reports.map { it.bytesDone }).isInOrder()
    }

    @Test
    fun `cancelling from the progress callback stops the compression`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { writer.write(OutputStream.nullOutputStream(), manifestSource(), sources(), null) { throw CancellationException("stop") } }
        }
    }
}
