package io.sonora.core.fat

/** A classic MBR with one primary FAT32 (LBA) partition and no boot code. */
internal object MasterBootRecord {
    private const val DISK_SIGNATURE = 0x1B8
    private const val FIRST_ENTRY = 0x1BE
    private const val TYPE_FAT32_LBA = 0x0C
    private const val HEADS = 255
    private const val SECTORS_PER_TRACK = 63
    private const val MAX_CYLINDER = 1023

    fun build(layout: VolumeLayout, diskSignature: Int): ByteArray = ByteArray(layout.blockSize).apply {
        putLe32(DISK_SIGNATURE, diskSignature.toLong())
        this[FIRST_ENTRY] = 0x00 // not active: the stick is never booted
        chs(layout.partitionStart).copyInto(this, FIRST_ENTRY + 1)
        this[FIRST_ENTRY + 4] = TYPE_FAT32_LBA.toByte()
        chs(layout.partitionStart + layout.partitionSectors - 1).copyInto(this, FIRST_ENTRY + 5)
        putLe32(FIRST_ENTRY + 8, layout.partitionStart)
        putLe32(FIRST_ENTRY + 12, layout.partitionSectors)
        putBootSignature()
    }

    /**
     * The legacy cylinder/head/sector address of [lba] with the conventional 255 heads and 63 sectors per track, or
     * the FE FF FF marker past cylinder 1023 that tells readers to use the LBA fields.
     */
    private fun chs(lba: Long): ByteArray {
        val cylinder = lba / (HEADS * SECTORS_PER_TRACK)
        if (cylinder > MAX_CYLINDER) return byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val head = lba / SECTORS_PER_TRACK % HEADS
        val sector = lba % SECTORS_PER_TRACK + 1
        return byteArrayOf(head.toByte(), (sector or (cylinder shr 2 and 0xC0)).toByte(), cylinder.toByte())
    }
}

internal fun ByteArray.putLe16(at: Int, value: Int) {
    this[at] = value.toByte()
    this[at + 1] = (value shr 8).toByte()
}

internal fun ByteArray.putLe32(at: Int, value: Long) {
    for (i in 0 until 4) this[at + i] = (value shr 8 * i).toByte()
}

/** 0x55 0xAA at offset 510, which MBR and FAT boot sectors carry whatever the sector size. */
internal fun ByteArray.putBootSignature() {
    this[510] = 0x55
    this[511] = 0xAA.toByte()
}
