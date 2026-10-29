package io.sonora.core.domain.storage

import java.io.IOException

/**
 * A raw block device addressed by logical block address (LBA), such as a USB mass-storage stick. Calls block and throw
 * [IOException] when the device fails; callers run them on an IO dispatcher. A range outside `0 until blockCount` is a
 * programming error ([IllegalArgumentException]), and [buffer] must hold `blocks * blockSize` bytes from [offset].
 */
interface BlockDevice {
    /** Bytes per logical block: 512 or 4096 on the devices Sonora formats. */
    val blockSize: Int

    /** Number of logical blocks; the last LBA is `blockCount - 1`. */
    val blockCount: Long

    @Throws(IOException::class)
    fun read(lba: Long, buffer: ByteArray, offset: Int = 0, blocks: Int)

    @Throws(IOException::class)
    fun write(lba: Long, buffer: ByteArray, offset: Int = 0, blocks: Int)

    /** Makes every completed write durable (e.g. SCSI SYNCHRONIZE CACHE). */
    @Throws(IOException::class)
    fun flush()
}
