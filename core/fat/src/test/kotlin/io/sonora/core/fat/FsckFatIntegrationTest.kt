package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.domain.storage.VolumeLabel
import java.io.File
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Formats sparse disk images and checks the partition with dosfstools' `fsck.fat -n` (read-only) and `fatlabel`, looked
 * up on `PATH` and in the usual sbin directories. Skipped when dosfstools is not installed, unless the environment sets
 * `FSCK_REQUIRED=1` (as CI should), in which case a missing tool fails the test.
 */
class FsckFatIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fsck = findTool("fsck.fat")
    private val fatlabel = findTool("fatlabel")

    @Before
    fun requireDosfstools() {
        if (System.getenv("FSCK_REQUIRED") == "1") {
            assertWithMessage("FSCK_REQUIRED=1 but fsck.fat was not found").that(fsck).isNotNull()
            assertWithMessage("FSCK_REQUIRED=1 but fatlabel was not found").that(fatlabel).isNotNull()
        }
        assumeTrue("fsck.fat is not installed", fsck != null)
    }

    @Test
    fun `64 MiB image with a label passes fsck`() = formatAndCheck(64 * MiB, label = "SONORA")

    @Test
    fun `4 GiB image without a label passes fsck`() = formatAndCheck(4 * GiB, label = null)

    @Test
    fun `40 GiB image passes fsck`() = formatAndCheck(40 * GiB, label = "CDJ 40G")

    @Test
    fun `4 GiB image with 4096-byte sectors passes fsck`() = formatAndCheck(4 * GiB, label = "4K", blockSize = 4096)

    private fun formatAndCheck(bytes: Long, label: String?, blockSize: Int = 512) {
        val image = temp.newFile("disk.img")
        val result = FileBlockDevice(image, blockSize, bytes / blockSize).use { device ->
            UsbVolumeFormatter().format(device, FormatOptions(label?.let(VolumeLabel::of)))
        }
        assertThat(result).isInstanceOf(FormatResult.Success::class.java)

        val partition = extractPartition(image, blockSize)
        val output = exec(checkNotNull(fsck).path, "-n", "-v", partition.path)
        assertWithMessage(output.text).that(output.exitCode).isEqualTo(0)
        assertWithMessage(output.text).that(output.text.lowercase()).doesNotContain("error")
        assertWithMessage(output.text).that(output.text.lowercase()).doesNotContain("corrupt")
        if (label != null && fatlabel != null) {
            assertThat(exec(fatlabel.path, partition.path).text.trim()).isEqualTo(label)
        }
    }

    /** Copies the MBR's first partition into its own sparse file, skipping all-zero chunks so holes stay holes. */
    private fun extractPartition(image: File, blockSize: Int): File {
        val target = temp.newFile("partition.img")
        RandomAccessFile(image, "r").use { disk ->
            val mbr = ByteArray(512).also { disk.readFully(it) }
            val start = mbr.u32(0x1C6) * blockSize
            val length = mbr.u32(0x1CA) * blockSize
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(length)
                val chunk = ByteArray(MiB.toInt())
                val zero = ByteArray(chunk.size)
                var done = 0L
                while (done < length) {
                    val size = minOf(chunk.size.toLong(), length - done).toInt()
                    disk.seek(start + done)
                    disk.readFully(chunk, 0, size)
                    if (Arrays.mismatch(chunk, 0, size, zero, 0, size) != -1) {
                        out.seek(done)
                        out.write(chunk, 0, size)
                    }
                    done += size
                }
            }
        }
        return target
    }

    private class Output(val exitCode: Int, val text: String)

    /**
     * Runs [command] with stdout and stderr going to a file, so a process that hangs cannot block the read: after
     * [TIMEOUT_SECONDS] it is killed and the test fails with whatever it printed.
     */
    private fun exec(vararg command: String): Output {
        val log = temp.newFile()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(log).start()
        process.outputStream.close() // EOF on stdin: never wait for an answer to a prompt
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
            fail("${command.joinToString(" ")} timed out after $TIMEOUT_SECONDS s:\n${log.readText()}")
        }
        return Output(process.exitValue(), log.readText())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val KILL_GRACE_SECONDS = 5L
        val SBIN_DIRS = listOf("/usr/sbin", "/sbin", "/usr/local/sbin")

        /** The first executable [name] on `PATH`, then in [SBIN_DIRS] (often missing from a user's `PATH`). */
        fun findTool(name: String): File? {
            val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
            return (path + SBIN_DIRS).distinct().map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
        }
    }
}
