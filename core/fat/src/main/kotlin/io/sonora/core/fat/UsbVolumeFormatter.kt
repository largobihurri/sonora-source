package io.sonora.core.fat

import io.sonora.core.domain.storage.BlockDevice
import io.sonora.core.fat.Fat32Structures.Companion.BACKUP_BOOT_SECTOR
import io.sonora.core.fat.Fat32Structures.Companion.BACKUP_FS_INFO_SECTOR
import io.sonora.core.fat.Fat32Structures.Companion.FS_INFO_SECTOR
import io.sonora.core.fat.FormatResult.Structure
import java.io.IOException
import kotlin.random.Random

/**
 * Formats a whole [BlockDevice] the way CDJs and every desktop OS read a USB stick: an MBR with one FAT32 (LBA)
 * partition from 1 MiB to the end of the device, holding a fresh FAT32 volume (see [VolumeGeometry]).
 *
 * Besides the new structures it zeroes the alignment gap (a stale MBR, GPT header or other signature) and the last
 * MiB of the device (a stale backup GPT), but never the data region, so formatting writes a few MiB plus the FATs.
 * The partition table is written last: a format that stops early leaves a device with no partition at all.
 *
 * The new volume keeps the volume id of the FAT volume the device held before ([ExistingVolumeId]); a device without
 * one gets a random id. The disk signature is always new.
 */
class UsbVolumeFormatter(private val random: Random = Random.Default) {

    /**
     * Blocking: run it on an IO dispatcher. [onProgress] is called after every write, and [isCancelled] is polled
     * before each one (by default, whether the thread is interrupted, which suits `runInterruptible`).
     */
    fun format(
        device: BlockDevice,
        options: FormatOptions,
        onProgress: (FormatProgress) -> Unit = {},
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): FormatResult {
        val layout = when (val plan = VolumeGeometry.plan(device.blockSize, device.blockCount)) {
            is VolumeLayout -> plan
            is GeometryFailure -> return plan
        }
        val diskSignature = nonZeroInt()
        // The stick's previous FAT volume id, when it has one: Android then sees the same volume and does not create
        // its standard folders (Alarms, DCIM, Music, ...) on it again. Read before anything is written.
        val volumeId = ExistingVolumeId.read(device) ?: nonZeroInt()
        val structures = Fat32Structures(layout, options.label, volumeId)
        val mbr = MasterBootRecord.build(layout, diskSignature)
        val start = layout.partitionStart
        val tailStart = maxOf(layout.blockCount - start, start + structures.systemSectors)

        val extents = listOf(
            Extent(lba = 0, blocks = start), // old MBR, GPT header and table, anything else before the partition
            Extent(lba = tailStart, blocks = layout.blockCount - tailStart), // backup GPT, NTFS backup boot sector
            // Everything but the boot sector first, so the volume only becomes recognisable once it is complete.
            Extent(lba = start + 1, blocks = structures.systemSectors - 1) { index, buffer, offset ->
                structures.render(1 + index, buffer, offset)
            },
            Extent(lba = start, blocks = 1) { _, buffer, offset -> structures.render(0, buffer, offset) },
            Extent(lba = 0, blocks = 1) { _, buffer, offset -> mbr.copyInto(buffer, offset) },
        )
        val writer = ExtentWriter(device, extents.sumOf { it.blocks } * layout.blockSize, onProgress, isCancelled)
        for (extent in extents) writer.write(extent)?.let { return it }
        try {
            device.flush()
        } catch (e: IOException) {
            return FormatResult.IoError(lba = null, cause = e)
        }

        val firstFat = start + structures.firstFatSector
        val expected = listOf(
            Triple(Structure.PARTITION_TABLE, 0L, mbr),
            Triple(Structure.BOOT_SECTOR, start, structures.bootSector),
            Triple(Structure.FS_INFO, start + FS_INFO_SECTOR, structures.fsInfo),
            Triple(Structure.BACKUP_BOOT_SECTOR, start + BACKUP_BOOT_SECTOR, structures.bootSector),
            Triple(Structure.BACKUP_FS_INFO, start + BACKUP_FS_INFO_SECTOR, structures.fsInfo),
            Triple(Structure.FAT, firstFat, structures.fatStart),
            Triple(Structure.BACKUP_FAT, firstFat + layout.fatSectors, structures.fatStart),
            Triple(Structure.ROOT_DIRECTORY, start + layout.dataStartSector, structures.rootStart),
        )
        val readBack = ByteArray(layout.blockSize)
        for ((structure, lba, written) in expected) {
            try {
                device.read(lba, readBack, blocks = 1)
            } catch (e: IOException) {
                return FormatResult.IoError(lba, e)
            }
            if (!readBack.contentEquals(written)) return FormatResult.VerificationMismatch(structure, lba)
        }
        return FormatResult.Success(layout, diskSignature, volumeId)
    }

    private fun nonZeroInt(): Int = generateSequence { random.nextInt() }.first { it != 0 }

    /** [blocks] blocks from [lba]; [content] fills block `index` of the extent into a zeroed buffer at `offset`. */
    private class Extent(
        val lba: Long,
        val blocks: Long,
        val content: (index: Long, buffer: ByteArray, offset: Int) -> Unit = { _, _, _ -> },
    )

    private class ExtentWriter(
        private val device: BlockDevice,
        private val totalBytes: Long,
        private val onProgress: (FormatProgress) -> Unit,
        private val isCancelled: () -> Boolean,
    ) {
        private val blockSize = device.blockSize
        private val buffer = ByteArray(CHUNK_BYTES)
        private var bytesWritten = 0L

        /** Writes [extent] in chunks of up to [CHUNK_BYTES]; returns why it stopped, or null when it is done. */
        fun write(extent: Extent): FormatResult.Failure? {
            var done = 0L
            while (done < extent.blocks) {
                if (isCancelled()) return FormatResult.Cancelled
                val blocks = minOf(CHUNK_BYTES / blockSize.toLong(), extent.blocks - done).toInt()
                buffer.fill(0, 0, blocks * blockSize)
                for (i in 0 until blocks) extent.content(done + i, buffer, i * blockSize)
                val lba = extent.lba + done
                try {
                    device.write(lba, buffer, blocks = blocks)
                } catch (e: IOException) {
                    return FormatResult.IoError(lba, e)
                }
                done += blocks
                bytesWritten += blocks.toLong() * blockSize
                onProgress(FormatProgress(bytesWritten, totalBytes))
            }
            return null
        }
    }

    private companion object {
        const val CHUNK_BYTES = 1 shl 20
    }
}
