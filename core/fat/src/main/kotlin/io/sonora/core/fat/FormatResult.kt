package io.sonora.core.fat

import io.sonora.core.domain.storage.VolumeLabel
import java.io.IOException

/** What to write besides the fixed MBR + FAT32 layout. Without a [label] the volume is "NO NAME". */
data class FormatOptions(val label: VolumeLabel? = null)

/** Bytes written so far out of the bytes the format writes in total (verification reads are not counted). */
data class FormatProgress(val bytesWritten: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes == 0L) 1f else bytesWritten.toFloat() / totalBytes
}

/** Either a [VolumeLayout] or why the device cannot hold one. */
sealed interface GeometryResult

/** A device whose geometry rules out the volume; nothing has been written. */
sealed interface GeometryFailure : GeometryResult, FormatResult.Failure

sealed interface FormatResult {
    data class Success(val layout: VolumeLayout, val diskSignature: Int, val volumeId: Int) : FormatResult

    sealed interface Failure : FormatResult

    data class UnsupportedBlockSize(val blockSize: Int) : GeometryFailure

    /** The device cannot fit a FAT32 volume of at least 65525 clusters. */
    data class DeviceTooSmall(val deviceBytes: Long) : GeometryFailure

    /**
     * The volume would need more sectors than an MBR partition entry can describe, or more clusters than FAT32 can
     * address. [maximumBytes] is the largest device size that formats, approximate for the cluster limit.
     */
    data class DeviceTooLarge(val deviceBytes: Long, val maximumBytes: Long) : GeometryFailure

    /** The device failed while accessing [lba]; null when flushing its cache. Earlier writes may have landed. */
    data class IoError(val lba: Long?, val cause: IOException) : Failure

    /** [structure] at [lba] read back different from what was written: the device does not store data reliably. */
    data class VerificationMismatch(val structure: Structure, val lba: Long) : Failure

    /**
     * Stopped on request, checked before each write. Cancelling before the first write leaves the device untouched;
     * later, the device may hold some of the new structures, but never the new partition table, which is written last
     * (an old one may already be wiped), so it should be formatted again. A write that fails is an [IoError], not this.
     */
    data object Cancelled : Failure

    enum class Structure {
        PARTITION_TABLE,
        BOOT_SECTOR,
        FS_INFO,
        BACKUP_BOOT_SECTOR,
        BACKUP_FS_INFO,

        /** The first sector of the first FAT: entries 0 and 1 (reserved) and 2 (the root directory's chain). */
        FAT,

        /** The first sector of the second FAT, which mirrors the first. */
        BACKUP_FAT,

        /** The first sector of the root directory cluster, holding the volume label entry when there is a label. */
        ROOT_DIRECTORY,
    }
}
