package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.VolumeLabel
import kotlin.random.Random
import org.junit.Test

class ExistingVolumeIdTest {

    private fun device() = SparseBlockDevice(512, 64 * MiB / 512)

    /** A FAT boot sector with [volumeId]: FAT32 (extended BPB at 64) or FAT12/16 (at 36). */
    private fun bootSector(volumeId: Int, fat32: Boolean = true, bytesPerSector: Int = 512, bootSig: Int = 0x29) =
        ByteArray(512).apply {
            this[0] = 0xEB.toByte()
            this[1] = 0x58
            this[2] = 0x90.toByte()
            putLe16(11, bytesPerSector)
            this[13] = 8
            putLe16(14, if (fat32) 32 else 1)
            this[16] = 2
            if (!fat32) putLe16(22, 200) // FATSz16
            val sigAt = if (fat32) 66 else 38
            this[sigAt] = bootSig.toByte()
            putLe32(sigAt + 1, volumeId.toLong())
            putBootSignature()
        }

    /** An MBR with one partition of [type] at [start]. */
    private fun mbr(type: Int, start: Long) = ByteArray(512).apply {
        this[0x1BE + 4] = type.toByte()
        putLe32(0x1BE + 8, start)
        putLe32(0x1BE + 12, 1000)
        putBootSignature()
    }

    @Test
    fun `the volume id of a FAT32 partition behind an MBR is found`() {
        val device = device()
        device.write(0, mbr(0x0C, 2048), blocks = 1)
        device.write(2048, bootSector(0x2C9C2151), blocks = 1)

        assertThat(ExistingVolumeId.read(device)).isEqualTo(0x2C9C2151)
    }

    @Test
    fun `a FAT16 partition and a stick without partition table are read too`() {
        val fat16 = device()
        fat16.write(0, mbr(0x06, 63), blocks = 1)
        fat16.write(63, bootSector(0x12345678, fat32 = false), blocks = 1)
        val superfloppy = device()
        superfloppy.write(0, bootSector(0x0BADF00D), blocks = 1)

        assertThat(ExistingVolumeId.read(fat16)).isEqualTo(0x12345678)
        assertThat(ExistingVolumeId.read(superfloppy)).isEqualTo(0x0BADF00D)
    }

    @Test
    fun `no readable FAT boot sector means no volume id`() {
        val blank = device()
        val notFat = device().apply {
            write(0, mbr(0x07, 2048), blocks = 1) // NTFS or exFAT
            write(2048, bootSector(0x11111111), blocks = 1)
        }
        val badBpb = device().apply {
            write(0, mbr(0x0C, 2048), blocks = 1)
            write(2048, bootSector(0x11111111, bytesPerSector = 300), blocks = 1)
        }
        val noExtendedBpb = device().apply {
            write(0, mbr(0x0C, 2048), blocks = 1)
            write(2048, bootSector(0x11111111, bootSig = 0), blocks = 1)
        }
        val zeroId = device().apply {
            write(0, mbr(0x0C, 2048), blocks = 1)
            write(2048, bootSector(0), blocks = 1)
        }
        val outside = device().apply { write(0, mbr(0x0C, 1L shl 30), blocks = 1) }

        listOf(blank, notFat, badBpb, noExtendedBpb, zeroId, outside).forEach {
            assertThat(ExistingVolumeId.read(it)).isNull()
        }
    }

    @Test
    fun `a stick that cannot be read has no volume id`() {
        val device = device()
        device.write(0, mbr(0x0C, 2048), blocks = 1)
        device.write(2048, bootSector(0x2C9C2151), blocks = 1)

        assertThat(ExistingVolumeId.read(FailingBlockDevice(device, failingReadLba = 0))).isNull()
        assertThat(ExistingVolumeId.read(FailingBlockDevice(device, failingReadLba = 2048))).isNull()
    }

    @Test
    fun `formatting again keeps the stick's volume id, so Android sees the same volume`() {
        val device = device()
        val first = UsbVolumeFormatter(Random(1)).format(device, FormatOptions(VolumeLabel.of("SONORA"))) as FormatResult.Success

        val second = UsbVolumeFormatter(Random(2)).format(device, FormatOptions(VolumeLabel.of("OTHER"))) as FormatResult.Success

        assertThat(second.volumeId).isEqualTo(first.volumeId)
        assertThat(device.block(2048).u32(67)).isEqualTo(first.volumeId.toLong() and 0xFFFF_FFFFL)
        assertThat(second.diskSignature).isNotEqualTo(first.diskSignature)
    }

    @Test
    fun `a stick formatted elsewhere keeps its volume id, a blank one gets a new one`() {
        val elsewhere = device().apply {
            write(0, mbr(0x0C, 8192), blocks = 1)
            write(8192, bootSector(0x2C9C2151), blocks = 1)
        }
        val blank = device()

        val kept = UsbVolumeFormatter(Random(1)).format(elsewhere, FormatOptions()) as FormatResult.Success
        val fresh = UsbVolumeFormatter(Random(1)).format(blank, FormatOptions()) as FormatResult.Success

        assertThat(kept.volumeId).isEqualTo(0x2C9C2151)
        assertThat(fresh.volumeId).isNotEqualTo(0)
        assertThat(fresh.volumeId).isNotEqualTo(0x2C9C2151)
    }
}
