package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeGeometryTest {

    private fun layout(bytes: Long, blockSize: Int = 512): VolumeLayout {
        val result = VolumeGeometry.plan(blockSize, bytes / blockSize)
        assertThat(result).isInstanceOf(VolumeLayout::class.java)
        return (result as VolumeLayout).also(::assertConsistent)
    }

    private fun assertConsistent(layout: VolumeLayout) = with(layout) {
        assertThat(partitionStart * blockSize).isEqualTo(MiB)
        assertThat(partitionStart + partitionSectors).isEqualTo(blockCount)
        assertThat(reservedSectors).isAtLeast(32)
        assertThat(reservedSectors).isLessThan(32 + sectorsPerCluster)
        assertThat(dataStartSector).isEqualTo(reservedSectors + 2 * fatSectors)
        assertThat(dataStartSector % sectorsPerCluster).isEqualTo(0)
        assertThat(clusterCount).isEqualTo((partitionSectors - dataStartSector) / sectorsPerCluster)
        assertThat(clusterCount).isAtLeast(65_525)
        // Both FATs hold an entry for every cluster plus the two reserved ones, without being grossly oversized.
        val neededFatSectors = ((clusterCount + 2) * 4 + blockSize - 1) / blockSize
        assertThat(fatSectors).isAtLeast(neededFatSectors)
        assertThat(fatSectors.toDouble()).isAtMost(neededFatSectors * 1.01 + 1)
    }

    @Test
    fun `cluster size follows the fatgen103 table`() {
        val expected = mapOf(
            64 * MiB to 512,
            260 * MiB to 512,
            300 * MiB to 4096,
            4 * GiB to 4096,
            8 * GiB + MiB to 4096,
            12 * GiB to 8192,
            16 * GiB + MiB to 8192,
            20 * GiB to 16_384,
            32 * GiB + MiB to 16_384,
            40 * GiB to 32_768,
            2048 * GiB to 32_768,
        )
        for ((bytes, clusterBytes) in expected) {
            assertThat(layout(bytes).clusterBytes).isEqualTo(clusterBytes)
        }
    }

    @Test
    fun `4096-byte sectors use the same byte-sized clusters and a 256-sector alignment`() {
        val small = layout(300 * MiB, blockSize = 4096)
        assertThat(small.partitionStart).isEqualTo(256)
        assertThat(small.sectorsPerCluster).isEqualTo(1)
        assertThat(layout(12 * GiB, blockSize = 4096).clusterBytes).isEqualTo(8192)
        assertThat(layout(40 * GiB, blockSize = 4096).sectorsPerCluster).isEqualTo(8)
    }

    @Test
    fun `reserved sectors grow so the data region starts on a cluster boundary`() {
        val sizes = listOf(300 * MiB, 4 * GiB + 7 * 512, 12 * GiB + 3 * MiB, 40 * GiB + 5 * 512, 100 * GiB)
        for (bytes in sizes) layout(bytes)
        assertThat(sizes.map { layout(it).reservedSectors }.any { it > 32 }).isTrue()
    }

    @Test
    fun `volumes that cannot reach 65525 clusters are too small`() {
        assertThat(VolumeGeometry.plan(512, 32 * MiB / 512)).isEqualTo(FormatResult.DeviceTooSmall(32 * MiB))
        assertThat(VolumeGeometry.plan(512, 0)).isEqualTo(FormatResult.DeviceTooSmall(0))
        assertThat(VolumeGeometry.plan(4096, 64 * MiB / 4096)).isEqualTo(FormatResult.DeviceTooSmall(64 * MiB))
        // 255 MiB of 4 KiB sectors leaves fewer than 65525 one-sector clusters.
        assertThat(VolumeGeometry.plan(4096, 256 * MiB / 4096)).isEqualTo(FormatResult.DeviceTooSmall(256 * MiB))
        assertThat(VolumeGeometry.plan(4096, 260 * MiB / 4096)).isInstanceOf(VolumeLayout::class.java)
    }

    @Test
    fun `a partition longer than the 32-bit MBR sector count is too large`() {
        val largest = 2048 + 0xFFFF_FFFFL
        assertThat(VolumeGeometry.plan(512, largest)).isInstanceOf(VolumeLayout::class.java)
        assertThat(VolumeGeometry.plan(512, largest + 1))
            .isEqualTo(FormatResult.DeviceTooLarge((largest + 1) * 512, maximumBytes = largest * 512))
    }

    @Test
    fun `more clusters than FAT32 can address is too large`() {
        val result = VolumeGeometry.plan(4096, 256 + 0xFFFF_FFFFL)
        assertThat(result).isInstanceOf(FormatResult.DeviceTooLarge::class.java)
        val tooLarge = result as FormatResult.DeviceTooLarge
        assertThat(tooLarge.maximumBytes).isLessThan(tooLarge.deviceBytes)
    }

    @Test
    fun `block sizes other than 512 and 4096 are unsupported`() {
        assertThat(VolumeGeometry.plan(1024, 1_000_000)).isEqualTo(FormatResult.UnsupportedBlockSize(1024))
        assertThat(VolumeGeometry.plan(2048, 1_000_000)).isEqualTo(FormatResult.UnsupportedBlockSize(2048))
    }
}
