package io.sonora.core.fat

import io.sonora.core.fat.FormatResult.DeviceTooLarge
import io.sonora.core.fat.FormatResult.DeviceTooSmall
import io.sonora.core.fat.FormatResult.UnsupportedBlockSize
import io.sonora.core.fat.VolumeLayout.Companion.FAT_COUNT

/** Where the partition and the FAT32 structures go. Partition-relative sectors count from [partitionStart]. */
data class VolumeLayout(
    val blockSize: Int,
    val blockCount: Long,
    val partitionStart: Long,
    val partitionSectors: Long,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatSectors: Long,
    val clusterCount: Long,
) : GeometryResult {
    val clusterBytes: Int get() = blockSize * sectorsPerCluster

    /** First sector of cluster 2 (the root directory), relative to the partition. */
    val dataStartSector: Long get() = reservedSectors + FAT_COUNT * fatSectors

    companion object {
        const val FAT_COUNT = 2
    }
}

/**
 * Plans one MBR partition starting at 1 MiB and spanning the rest of the device, holding a FAT32 volume laid out as
 * Microsoft's "FAT: General Overview of On-Disk Format" (fatgen103) prescribes: its cluster size table, 32 reserved
 * sectors, two FATs sized by its FATSz formula, at least 65525 clusters.
 */
object VolumeGeometry {
    const val ALIGNMENT_BYTES = 1L shl 20
    const val MIN_CLUSTERS = 65_525L

    /** Cluster numbers run from 2 to 0x0FFFFFEF; higher FAT entry values are reserved, bad-cluster or end-of-chain. */
    const val MAX_CLUSTERS = 0x0FFF_FFEEL

    private const val RESERVED_SECTORS = 32
    private const val MAX_CLUSTER_BYTES = 32_768L
    private const val MAX_MBR_SECTORS = 0xFFFF_FFFFL

    fun plan(blockSize: Int, blockCount: Long): GeometryResult {
        if (blockSize != 512 && blockSize != 4096) return UnsupportedBlockSize(blockSize)
        val deviceBytes = blockCount * blockSize
        val partitionStart = ALIGNMENT_BYTES / blockSize
        val partitionSectors = blockCount - partitionStart
        if (partitionSectors > MAX_MBR_SECTORS) {
            return DeviceTooLarge(deviceBytes, maximumBytes = (partitionStart + MAX_MBR_SECTORS) * blockSize)
        }
        val clusterBytes = clusterBytesFor(partitionSectors * blockSize) ?: return DeviceTooSmall(deviceBytes)
        val sectorsPerCluster = maxOf(clusterBytes, blockSize) / blockSize
        val fatSectors = fatSectors(partitionSectors, sectorsPerCluster, blockSize)
        // Grow the reserved region so the data region, and with it every cluster, starts on a cluster boundary.
        val misalignment = ((RESERVED_SECTORS + FAT_COUNT * fatSectors) % sectorsPerCluster).toInt()
        val reservedSectors = RESERVED_SECTORS + (sectorsPerCluster - misalignment) % sectorsPerCluster
        val clusterCount = (partitionSectors - reservedSectors - FAT_COUNT * fatSectors) / sectorsPerCluster
        return when {
            clusterCount < MIN_CLUSTERS -> DeviceTooSmall(deviceBytes)
            clusterCount > MAX_CLUSTERS ->
                DeviceTooLarge(deviceBytes, maximumBytes = ALIGNMENT_BYTES + MAX_CLUSTERS * MAX_CLUSTER_BYTES)
            else -> {
                // The FATSz formula only over-allocates; recheck that each FAT maps every cluster plus entries 0 and 1.
                check(fatSectors * blockSize / 4 >= clusterCount + 2) { "FAT of $fatSectors sectors for $clusterCount clusters" }
                VolumeLayout(
                    blockSize = blockSize,
                    blockCount = blockCount,
                    partitionStart = partitionStart,
                    partitionSectors = partitionSectors,
                    sectorsPerCluster = sectorsPerCluster,
                    reservedSectors = reservedSectors,
                    fatSectors = fatSectors,
                    clusterCount = clusterCount,
                )
            }
        }
    }

    /**
     * fatgen103's FAT32 cluster size table, keyed by volume size in 512-byte sectors and applied to the volume's size
     * in bytes, so 4096-byte-sector devices get the same cluster sizes (never smaller than one sector). Null when the
     * volume is too small for FAT32.
     */
    private fun clusterBytesFor(volumeBytes: Long): Int? = when {
        volumeBytes <= 66_600L * 512 -> null
        volumeBytes <= 532_480L * 512 -> 512
        volumeBytes <= 16_777_216L * 512 -> 4096
        volumeBytes <= 33_554_432L * 512 -> 8192
        volumeBytes <= 67_108_864L * 512 -> 16_384
        else -> 32_768
    }

    /**
     * fatgen103's FATSz32 formula with its 256 (the FAT16 entries per 512-byte sector) generalised to blockSize / 2,
     * so 4096-byte sectors do not get a FAT eight times too large. RootDirSectors is 0 on FAT32.
     */
    private fun fatSectors(totalSectors: Long, sectorsPerCluster: Int, blockSize: Int): Long {
        val tmpVal1 = totalSectors - RESERVED_SECTORS
        val tmpVal2 = (blockSize / 2L * sectorsPerCluster + FAT_COUNT) / 2
        return (tmpVal1 + tmpVal2 - 1) / tmpVal2
    }
}
