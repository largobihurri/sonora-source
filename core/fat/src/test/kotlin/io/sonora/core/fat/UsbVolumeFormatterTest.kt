package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.domain.storage.BlockDevice
import io.sonora.core.domain.storage.VolumeLabel
import kotlin.random.Random
import org.junit.Test

class UsbVolumeFormatterTest {

    private val formatter = UsbVolumeFormatter(Random(42))

    private fun device(bytes: Long, blockSize: Int = 512) = SparseBlockDevice(blockSize, bytes / blockSize)

    private fun format(device: BlockDevice, label: String? = null): FormatResult.Success {
        val result = formatter.format(device, FormatOptions(label?.let(VolumeLabel::of)))
        assertThat(result).isInstanceOf(FormatResult.Success::class.java)
        return result as FormatResult.Success
    }

    @Test
    fun `MBR holds one FAT32 LBA partition aligned to 1 MiB that covers the rest of the disk`() {
        val device = device(64 * MiB)
        val result = format(device)
        val mbr = device.block(0)

        assertThat(mbr.u32(0x1B8)).isEqualTo(result.diskSignature.toLong() and 0xFFFF_FFFFL)
        assertThat(result.diskSignature).isNotEqualTo(0)
        assertThat(mbr.u16(0x1BC)).isEqualTo(0)
        val entry = 0x1BE
        assertThat(mbr.u8(entry)).isEqualTo(0x00)
        assertThat(mbr.copyOfRange(entry + 1, entry + 4)).isEqualTo(bytes(0x20, 0x21, 0x00))
        assertThat(mbr.u8(entry + 4)).isEqualTo(0x0C)
        // Last LBA 131071 = cylinder 8, head 40, sector 32 with 255 heads and 63 sectors per track.
        assertThat(mbr.copyOfRange(entry + 5, entry + 8)).isEqualTo(bytes(0x28, 0x20, 0x08))
        assertThat(mbr.u32(entry + 8)).isEqualTo(2048)
        assertThat(mbr.u32(entry + 12)).isEqualTo(64 * MiB / 512 - 2048)
        assertThat(mbr.copyOfRange(entry + 16, 510).all { it == 0.toByte() }).isTrue()
        assertThat(mbr.u8(510)).isEqualTo(0x55)
        assertThat(mbr.u8(511)).isEqualTo(0xAA)
    }

    @Test
    fun `CHS addresses beyond 1024 cylinders use the overflow marker`() {
        val device = device(40 * GiB)
        format(device)
        val mbr = device.block(0)

        assertThat(mbr.copyOfRange(0x1BF, 0x1C2)).isEqualTo(bytes(0x20, 0x21, 0x00))
        assertThat(mbr.copyOfRange(0x1C3, 0x1C6)).isEqualTo(bytes(0xFE, 0xFF, 0xFF))
    }

    @Test
    fun `boot sector carries the fatgen103 FAT32 BPB`() {
        val device = device(4 * GiB)
        val result = format(device, label = "cdj usb")
        val layout = result.layout
        val boot = device.block(2048)

        assertThat(boot.copyOfRange(0, 3)).isEqualTo(bytes(0xEB, 0x58, 0x90))
        assertThat(boot.ascii(3, 8)).isEqualTo("MSWIN4.1")
        assertThat(boot.u16(11)).isEqualTo(512)
        assertThat(boot.u8(13)).isEqualTo(8)
        assertThat(boot.u16(14)).isEqualTo(layout.reservedSectors)
        assertThat(boot.u8(16)).isEqualTo(2)
        assertThat(boot.u16(17)).isEqualTo(0)
        assertThat(boot.u16(19)).isEqualTo(0)
        assertThat(boot.u8(21)).isEqualTo(0xF8)
        assertThat(boot.u16(22)).isEqualTo(0)
        assertThat(boot.u16(24)).isEqualTo(63)
        assertThat(boot.u16(26)).isEqualTo(255)
        assertThat(boot.u32(28)).isEqualTo(2048)
        assertThat(boot.u32(32)).isEqualTo(layout.partitionSectors)
        assertThat(boot.u32(36)).isEqualTo(layout.fatSectors)
        assertThat(boot.u16(40)).isEqualTo(0)
        assertThat(boot.u16(42)).isEqualTo(0)
        assertThat(boot.u32(44)).isEqualTo(2)
        assertThat(boot.u16(48)).isEqualTo(1)
        assertThat(boot.u16(50)).isEqualTo(6)
        assertThat(boot.copyOfRange(52, 64).all { it == 0.toByte() }).isTrue()
        assertThat(boot.u8(64)).isEqualTo(0x80)
        assertThat(boot.u8(65)).isEqualTo(0)
        assertThat(boot.u8(66)).isEqualTo(0x29)
        assertThat(boot.u32(67)).isEqualTo(result.volumeId.toLong() and 0xFFFF_FFFFL)
        assertThat(boot.ascii(71, 11)).isEqualTo("CDJ USB    ")
        assertThat(boot.ascii(82, 8)).isEqualTo("FAT32   ")
        assertThat(boot.u8(90)).isNotEqualTo(0)
        assertThat(boot.u8(510)).isEqualTo(0x55)
        assertThat(boot.u8(511)).isEqualTo(0xAA)
    }

    @Test
    fun `FSInfo records the free cluster count and the next free cluster`() {
        val device = device(4 * GiB)
        val layout = format(device).layout
        val fsInfo = device.block(2048 + 1)

        assertThat(fsInfo.u32(0)).isEqualTo(0x41615252)
        assertThat(fsInfo.copyOfRange(4, 484).all { it == 0.toByte() }).isTrue()
        assertThat(fsInfo.u32(484)).isEqualTo(0x61417272)
        assertThat(fsInfo.u32(488)).isEqualTo(layout.clusterCount - 1)
        assertThat(fsInfo.u32(492)).isEqualTo(3)
        assertThat(fsInfo.u32(508)).isEqualTo(0xAA550000L)
    }

    @Test
    fun `backup boot sector and backup FSInfo are identical copies`() {
        val device = device(12 * GiB)
        format(device, label = "BACKUP")

        assertThat(device.block(2048 + 6)).isEqualTo(device.block(2048))
        assertThat(device.block(2048 + 7)).isEqualTo(device.block(2048 + 1))
    }

    @Test
    fun `both FATs reserve entries 0 and 1 and end the root directory chain`() {
        val device = device(300 * MiB)
        val layout = format(device).layout
        for (fat in 0 until 2) {
            val first = 2048 + layout.reservedSectors + fat * layout.fatSectors
            val sector = device.block(first)
            assertThat(sector.u32(0)).isEqualTo(0x0FFFFFF8)
            assertThat(sector.u32(4)).isEqualTo(0x0FFFFFFF)
            assertThat(sector.u32(8)).isEqualTo(0x0FFFFFF8)
            assertThat(sector.copyOfRange(12, 512).all { it == 0.toByte() }).isTrue()
            assertThat(device.block(first + layout.fatSectors - 1).all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `root directory cluster 2 starts with the volume label entry`() {
        val device = device(64 * MiB)
        val layout = format(device, label = "My USB").layout
        val root = device.block(2048 + layout.dataStartSector)

        assertThat(root.ascii(0, 11)).isEqualTo("MY USB     ")
        assertThat(root.u8(11)).isEqualTo(0x08)
        assertThat(root.copyOfRange(12, 512).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `without a label the boot sector says NO NAME and the root directory is empty`() {
        val device = device(64 * MiB)
        val layout = format(device).layout

        assertThat(device.block(2048).ascii(71, 11)).isEqualTo("NO NAME    ")
        assertThat(device.block(2048 + layout.dataStartSector).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `stale signatures and metadata are wiped while the data region is left alone`() {
        val device = device(64 * MiB)
        val layout = VolumeGeometry.plan(512, device.blockCount) as VolumeLayout
        val last = device.blockCount - 1
        val firstCluster = 2048 + layout.dataStartSector
        val wiped = listOf(
            1L, 2L, 2047L, // GPT header and partition entries, alignment gap
            2048L + 2, 2048L + 31, // old reserved sectors
            2048L + layout.reservedSectors + 5, 2048L + layout.dataStartSector - 1, // old FAT contents
            firstCluster + layout.sectorsPerCluster - 1, // old root directory
            last - 2047, last - 33, last, // backup GPT at the end of the disk
        )
        val kept = listOf(firstCluster + layout.sectorsPerCluster, firstCluster + 50_000, last - 2048)
        for (lba in wiped + kept) device.fill(lba, 0x5A)

        format(device)

        for (lba in wiped) assertThat(device.block(lba).all { it == 0.toByte() }).isTrue()
        for (lba in kept) assertThat(device.block(lba).all { it == 0x5A.toByte() }).isTrue()
    }

    @Test
    fun `a 4096-byte sector device gets 256-sector alignment and 4096-byte BPB sectors`() {
        val device = device(4 * GiB, blockSize = 4096)
        val layout = format(device).layout
        val mbr = device.block(0)
        val boot = device.block(256)

        assertThat(mbr.u32(0x1C6)).isEqualTo(256)
        assertThat(mbr.u32(0x1CA)).isEqualTo(4 * GiB / 4096 - 256)
        assertThat(mbr.u16(510)).isEqualTo(0xAA55)
        assertThat(boot.u16(11)).isEqualTo(4096)
        assertThat(boot.u8(13)).isEqualTo(1)
        assertThat(boot.u32(28)).isEqualTo(256)
        assertThat(boot.u16(510)).isEqualTo(0xAA55)
        assertThat(device.block(256 + 1).u32(508)).isEqualTo(0xAA550000L)
        assertThat(device.block(256 + 6)).isEqualTo(boot)
        assertThat(device.block(256L + layout.reservedSectors).u32(8)).isEqualTo(0x0FFFFFF8)
    }

    @Test
    fun `progress grows to the total and large devices need only a few MiB of writes`() {
        val device = device(40 * GiB)
        val progress = mutableListOf<FormatProgress>()

        formatter.format(device, FormatOptions(), onProgress = { progress += it })

        assertThat(progress).isNotEmpty()
        assertThat(progress.map { it.bytesWritten }).isInStrictOrder()
        assertThat(progress.map { it.totalBytes }.distinct()).containsExactly(device.bytesWritten)
        assertThat(progress.last().bytesWritten).isEqualTo(device.bytesWritten)
        assertThat(progress.last().fraction).isEqualTo(1f)
        assertThat(device.bytesWritten).isLessThan(16 * MiB)
        assertThat(device.flushes).isAtLeast(1)
    }

    @Test
    fun `devices that cannot hold the volume are rejected before anything is written`() {
        val small = device(32 * MiB)
        val huge = SparseBlockDevice(512, 2048 + 0x1_0000_0000L)

        assertThat(formatter.format(small, FormatOptions())).isEqualTo(FormatResult.DeviceTooSmall(32 * MiB))
        assertThat(formatter.format(huge, FormatOptions())).isInstanceOf(FormatResult.DeviceTooLarge::class.java)
        assertThat(small.bytesWritten + huge.bytesWritten).isEqualTo(0)
    }

    @Test
    fun `cancelling stops before the partition table is written`() {
        val device = device(4 * GiB)
        var cancelled = false

        val result = formatter.format(device, FormatOptions(), onProgress = { cancelled = true }, isCancelled = { cancelled })

        assertThat(result).isEqualTo(FormatResult.Cancelled)
        assertThat(device.block(0).u16(510)).isEqualTo(0)
    }

    @Test
    fun `a block that reads back differently is a verification mismatch`() {
        val cases = mapOf(
            2048L + 6 to FormatResult.Structure.BACKUP_BOOT_SECTOR,
            2048L + 1 to FormatResult.Structure.FS_INFO,
            0L to FormatResult.Structure.PARTITION_TABLE,
        )
        for ((lba, structure) in cases) {
            val device = CorruptingBlockDevice(device(64 * MiB), corruptLba = lba)
            assertThat(formatter.format(device, FormatOptions()))
                .isEqualTo(FormatResult.VerificationMismatch(structure, lba))
        }
    }

    @Test
    fun `a corrupted first sector of either FAT or of the root directory is a verification mismatch`() {
        val layout = layout64MiB()
        val firstFat = 2048L + layout.reservedSectors
        val cases = mapOf(
            firstFat to FormatResult.Structure.FAT,
            firstFat + layout.fatSectors to FormatResult.Structure.BACKUP_FAT,
            2048L + layout.dataStartSector to FormatResult.Structure.ROOT_DIRECTORY,
        )
        for (label in listOf(null, "SONORA")) {
            for ((lba, structure) in cases) {
                val device = CorruptingBlockDevice(device(64 * MiB), corruptLba = lba)
                assertWithMessage("$structure, label $label")
                    .that(formatter.format(device, FormatOptions(label?.let(VolumeLabel::of))))
                    .isEqualTo(FormatResult.VerificationMismatch(structure, lba))
            }
        }
    }

    @Test
    fun `a failing write is an I-O error at the LBA of that write`() {
        val device = FailingBlockDevice(device(64 * MiB), failingWriteLba = 0)

        val result = formatter.format(device, FormatOptions())

        assertThat(result).isInstanceOf(FormatResult.IoError::class.java)
        assertThat((result as FormatResult.IoError).lba).isEqualTo(0)
    }

    @Test
    fun `a failing flush is an I-O error without an LBA`() {
        val device = FailingBlockDevice(device(64 * MiB), failFlush = true)

        val result = formatter.format(device, FormatOptions())

        assertThat(result).isInstanceOf(FormatResult.IoError::class.java)
        assertThat((result as FormatResult.IoError).lba).isNull()
        assertThat(result.cause).hasMessageThat().isEqualTo("flush failed")
    }

    @Test
    fun `a read failing during verification is an I-O error at the verified LBA`() {
        val layout = layout64MiB()
        val verified = listOf(
            0L, // partition table
            2048L, // boot sector
            2048L + 7, // backup FSInfo
            2048L + layout.reservedSectors, // first FAT
            2048L + layout.reservedSectors + layout.fatSectors, // second FAT
            2048L + layout.dataStartSector, // root directory
        )
        for (lba in verified) {
            val device = FailingBlockDevice(device(64 * MiB), failingReadLba = lba)

            val result = formatter.format(device, FormatOptions())

            assertWithMessage("LBA $lba").that(result).isInstanceOf(FormatResult.IoError::class.java)
            assertWithMessage("LBA $lba").that((result as FormatResult.IoError).lba).isEqualTo(lba)
            assertThat(result.cause).hasMessageThat().isEqualTo("read failed")
        }
    }

    private fun layout64MiB() = VolumeGeometry.plan(512, 64 * MiB / 512) as VolumeLayout

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
