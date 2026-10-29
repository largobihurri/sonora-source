package io.sonora.core.fat

import io.sonora.core.domain.storage.VolumeLabel

/**
 * The FAT32 system area of a [layout] (fatgen103): boot sector and FSInfo with their backups in the reserved region,
 * both FATs, and the root directory in cluster 2. Every other sector of the area is zero.
 */
internal class Fat32Structures(private val layout: VolumeLayout, label: VolumeLabel?, volumeId: Int) {

    /** Sectors from the partition start through the root directory cluster. */
    val systemSectors: Long = layout.dataStartSector + layout.sectorsPerCluster

    private val labelBytes = (label?.text ?: NO_NAME).padEnd(LABEL_LENGTH).toByteArray(Charsets.US_ASCII)

    val bootSector: ByteArray = sector().apply {
        byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()).copyInto(this, 0) // JMP to the boot code at 90, NOP
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(this, 3)
        putLe16(11, layout.blockSize)
        this[13] = layout.sectorsPerCluster.toByte()
        putLe16(14, layout.reservedSectors)
        this[16] = VolumeLayout.FAT_COUNT.toByte()
        // RootEntCnt, TotSec16 and FATSz16 stay 0 on FAT32.
        this[21] = MEDIA_FIXED.toByte()
        putLe16(24, SECTORS_PER_TRACK)
        putLe16(26, HEADS)
        putLe32(28, layout.partitionStart) // HiddSec
        putLe32(32, layout.partitionSectors)
        putLe32(36, layout.fatSectors)
        // ExtFlags 0 mirrors every FAT; FSVer 0.0.
        putLe32(44, ROOT_CLUSTER.toLong())
        putLe16(48, FS_INFO_SECTOR.toInt())
        putLe16(50, BACKUP_BOOT_SECTOR.toInt())
        this[64] = 0x80.toByte() // DrvNum: hard disk
        this[66] = 0x29 // BootSig: VolID, VolLab and FilSysType follow
        putLe32(67, volumeId.toLong())
        labelBytes.copyInto(this, 71)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(this, 82)
        BOOT_STUB.copyInto(this, 90)
        putBootSignature()
    }

    val fsInfo: ByteArray = sector().apply {
        putLe32(0, 0x41615252) // "RRaA"
        putLe32(484, 0x61417272) // "rrAa"
        putLe32(488, layout.clusterCount - 1) // free: all but the root directory's cluster
        putLe32(492, ROOT_CLUSTER + 1L) // next free hint
        putLe32(508, 0xAA550000L)
    }

    /** First sector of each FAT, at partition-relative [firstFatSector] and [firstFatSector] + `fatSectors`. */
    val fatStart: ByteArray = sector().apply {
        putLe32(0, 0x0FFFFF00L or MEDIA_FIXED.toLong()) // FAT[0]: media byte
        putLe32(4, 0x0FFFFFFF) // FAT[1]: end of chain, clean shutdown, no hard error
        putLe32(8, END_OF_CHAIN) // FAT[2]: the root directory is one cluster
    }

    /** First sector of the root directory cluster: the volume label entry, or all zeros without a label. */
    val rootStart: ByteArray = sector().apply {
        if (label != null) {
            labelBytes.copyInto(this, 0)
            this[11] = ATTR_VOLUME_ID
        }
    }

    val firstFatSector: Long = layout.reservedSectors.toLong()

    /** Copies partition-relative [sector] into [buffer] at [offset], which already holds zeros. */
    fun render(sector: Long, buffer: ByteArray, offset: Int) {
        val content = when (sector) {
            0L, BACKUP_BOOT_SECTOR -> bootSector
            FS_INFO_SECTOR, BACKUP_FS_INFO_SECTOR -> fsInfo
            firstFatSector, firstFatSector + layout.fatSectors -> fatStart
            layout.dataStartSector -> rootStart
            else -> null
        }
        content?.copyInto(buffer, offset)
    }

    private fun sector() = ByteArray(layout.blockSize)

    companion object {
        const val FS_INFO_SECTOR = 1L
        const val BACKUP_BOOT_SECTOR = 6L
        const val BACKUP_FS_INFO_SECTOR = 7L

        private const val ROOT_CLUSTER = 2
        private const val MEDIA_FIXED = 0xF8
        private const val END_OF_CHAIN = 0x0FFFFFF8L
        private const val ATTR_VOLUME_ID: Byte = 0x08
        private const val SECTORS_PER_TRACK = 63
        private const val HEADS = 255
        private const val LABEL_LENGTH = 11
        private const val NO_NAME = "NO NAME"

        /** Not bootable: INT 18h hands over to the next boot device; halt if the BIOS returns. */
        private val BOOT_STUB = byteArrayOf(0xCD.toByte(), 0x18, 0xFA.toByte(), 0xF4.toByte(), 0xEB.toByte(), 0xFD.toByte())
    }
}
