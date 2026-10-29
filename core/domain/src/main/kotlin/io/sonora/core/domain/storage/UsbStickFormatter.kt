package io.sonora.core.domain.storage

import java.io.IOException

/**
 * Erases a whole USB stick and writes the layout Pioneer players read: an MBR with one FAT32 partition. Everything on
 * the device is lost. Blocking: run it on an IO dispatcher.
 */
interface UsbStickFormatter {

    /**
     * Formats [device] with [label] (or "NO NAME" without one). [onProgress] is called after every write;
     * [isCancelled] is polled before each write, and returning true stops with [StickFormatResult.Cancelled].
     */
    fun format(
        device: BlockDevice,
        label: VolumeLabel?,
        onProgress: (StickFormatProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): StickFormatResult
}

/** Bytes written so far out of the bytes the format writes in total. */
data class StickFormatProgress(val bytesWritten: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0L) 1f else (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f)
}

sealed interface StickFormatResult {

    /** The stick holds an empty FAT32 volume of [volumeBytes] with [clusterBytes] clusters. */
    data class Success(val label: VolumeLabel?, val volumeBytes: Long, val clusterBytes: Int) : StickFormatResult

    sealed interface Failure : StickFormatResult

    /** Nothing was written: the device uses blocks of a size the formatter does not handle. */
    data class UnsupportedBlockSize(val blockSize: Int) : Failure

    /** Nothing was written: the device cannot hold a FAT32 volume. */
    data class TooSmall(val deviceBytes: Long) : Failure

    /** Nothing was written: an MBR partition (or FAT32) cannot describe a device this large. */
    data class TooLarge(val deviceBytes: Long, val maximumBytes: Long) : Failure

    /** The device failed at [lba] (null while flushing its cache); the stick must be formatted again. */
    data class IoError(val lba: Long?, val cause: IOException) : Failure

    /** [structure] at [lba] read back different from what was written: the stick does not store data reliably. */
    data class VerificationMismatch(val structure: FormatStructure, val lba: Long) : Failure

    /** Stopped on request; unless it stopped before the first write, the stick must be formatted again. */
    data object Cancelled : Failure
}

/** The on-disk structures a format writes and reads back. */
enum class FormatStructure {
    PARTITION_TABLE,
    BOOT_SECTOR,
    FS_INFO,
    BACKUP_BOOT_SECTOR,
    BACKUP_FS_INFO,
    FAT,
    BACKUP_FAT,
    ROOT_DIRECTORY,
}
