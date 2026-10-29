package io.sonora.core.fat

import io.sonora.core.domain.storage.BlockDevice
import java.io.IOException

/**
 * The volume id (serial number, `BS_VolID`) of the FAT volume a device holds before it is formatted: in the first FAT
 * partition of its MBR, or at block 0 for a stick without a partition table.
 *
 * Android names a removable volume after this id (`2C9C-2151`) and its media provider creates its standard folders
 * (Alarms, DCIM, Music, ...) once per new id, so formatting with the stick's previous id keeps them from coming back.
 */
internal object ExistingVolumeId {
    /** MBR partition types of FAT12, FAT16 and FAT32 volumes (CHS and LBA). */
    private val FAT_TYPES = setOf(0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E)
    private const val FIRST_ENTRY = 0x1BE
    private const val ENTRY_SIZE = 16
    private const val ENTRIES = 4

    /** The id, or `null` when the device holds no readable FAT boot sector (blank, exFAT, NTFS, unreadable, id 0). */
    fun read(device: BlockDevice): Int? = try {
        val first = block(device, 0)
        volumeIdOf(first, device.blockSize) ?: fatPartitionStart(first, device.blockCount)?.let {
            volumeIdOf(block(device, it), device.blockSize)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun block(device: BlockDevice, lba: Long) = ByteArray(device.blockSize).also { device.read(lba, it, blocks = 1) }

    /** The first FAT partition of an MBR that starts inside the device, if [mbr] is one. */
    internal fun fatPartitionStart(mbr: ByteArray, blockCount: Long): Long? {
        if (!hasBootSignature(mbr)) return null
        return (0 until ENTRIES).map { FIRST_ENTRY + it * ENTRY_SIZE }
            .firstOrNull { entry -> mbr.u8(entry + 4) in FAT_TYPES && mbr.u32(entry + 8) in 1 until blockCount }
            ?.let { entry -> mbr.u32(entry + 8) }
    }

    /**
     * The volume id of [sector] if it is a FAT boot sector for blocks of [blockSize] bytes: a jump instruction, a
     * plausible BPB and the extended boot signature 0x29 (at 66 on FAT32, where `BPB_FATSz16` is 0; at 38 otherwise).
     */
    internal fun volumeIdOf(sector: ByteArray, blockSize: Int): Int? {
        if (!hasBootSignature(sector) || sector.u8(0) !in setOf(0xEB, 0xE9)) return null
        val sectorsPerCluster = sector.u8(13)
        val plausible = sector.u16(11) == blockSize && sectorsPerCluster != 0 &&
            sectorsPerCluster and (sectorsPerCluster - 1) == 0 && sector.u16(14) > 0 && sector.u8(16) in 1..2
        if (!plausible) return null
        val signatureAt = if (sector.u16(22) == 0) 66 else 38
        if (sector.u8(signatureAt) != EXTENDED_BOOT_SIGNATURE) return null
        return sector.u32(signatureAt + 1).toInt().takeIf { it != 0 }
    }

    private fun hasBootSignature(sector: ByteArray) = sector.size >= 512 && sector.u8(510) == 0x55 && sector.u8(511) == 0xAA

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))

    private const val EXTENDED_BOOT_SIGNATURE = 0x29
}
