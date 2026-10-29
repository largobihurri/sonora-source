package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.FormatStructure
import io.sonora.core.domain.storage.StickFormatProgress
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.VolumeLabel
import org.junit.Test

class FatUsbStickFormatterTest {

    private val formatter = FatUsbStickFormatter()
    private val label = VolumeLabel.of("SONORA")

    private fun device(bytes: Long, blockSize: Int = 512) = SparseBlockDevice(blockSize, bytes / blockSize)

    private fun format(device: SparseBlockDevice) = formatter.format(device, label, onProgress = {}, isCancelled = { false })

    @Test
    fun `a successful format reports the volume and every write as progress`() {
        val stick = device(64L shl 20)
        val progress = mutableListOf<StickFormatProgress>()

        val result = formatter.format(stick, label, onProgress = { progress += it }, isCancelled = { false })

        val expected = VolumeGeometry.plan(512, stick.blockCount) as VolumeLayout
        assertThat(result).isEqualTo(
            StickFormatResult.Success(label, volumeBytes = expected.partitionSectors * 512, clusterBytes = expected.clusterBytes),
        )
        assertThat(progress).isNotEmpty()
        assertThat(progress.last().fraction).isEqualTo(1f)
        assertThat(progress.map { it.bytesWritten }).isInStrictOrder()
    }

    @Test
    fun `geometry failures map to their stick format results`() {
        assertThat(format(device(8L shl 20))).isEqualTo(StickFormatResult.TooSmall(8L shl 20))
        assertThat(format(device(64L shl 20, blockSize = 2048))).isEqualTo(StickFormatResult.UnsupportedBlockSize(2048))
        val huge = SparseBlockDevice(512, 2048 + 0x1_0000_0000L)
        val tooLarge = format(huge) as StickFormatResult.TooLarge
        assertThat(tooLarge.deviceBytes).isEqualTo(huge.blockCount * 512)
        assertThat(tooLarge.maximumBytes).isLessThan(tooLarge.deviceBytes)
    }

    @Test
    fun `a failing write maps to an io error at its block`() {
        val failing = FailingBlockDevice(device(64L shl 20), failingWriteLba = 0)

        val result = formatter.format(failing, label, onProgress = {}, isCancelled = { false })

        assertThat(result).isInstanceOf(StickFormatResult.IoError::class.java)
        assertThat((result as StickFormatResult.IoError).lba).isEqualTo(0)
        assertThat(result.cause).hasMessageThat().isEqualTo("write failed")
    }

    @Test
    fun `a corrupted boot sector maps to a verification mismatch naming the structure`() {
        val corrupting = CorruptingBlockDevice(device(64L shl 20), corruptLba = 2048)

        val result = formatter.format(corrupting, label, onProgress = {}, isCancelled = { false })

        assertThat(result).isEqualTo(StickFormatResult.VerificationMismatch(FormatStructure.BOOT_SECTOR, 2048))
    }

    @Test
    fun `a cancelled format writes nothing and reports cancelled`() {
        val stick = device(64L shl 20)

        val result = formatter.format(stick, label, onProgress = {}, isCancelled = { true })

        assertThat(result).isEqualTo(StickFormatResult.Cancelled)
        assertThat(stick.bytesWritten).isEqualTo(0)
    }

    @Test
    fun `every verified structure has a domain counterpart`() {
        assertThat(FormatStructure.entries.map { it.name })
            .containsExactlyElementsIn(FormatResult.Structure.entries.map { it.name }).inOrder()
    }
}
