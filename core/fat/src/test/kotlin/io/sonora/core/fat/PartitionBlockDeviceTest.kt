package io.sonora.core.fat

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PartitionBlockDeviceTest {

    private val disk = SparseBlockDevice(512, 100)
    private val partition = PartitionBlockDevice(disk, startLba = 10, blockCount = 80)

    @Test
    fun `blocks are addressed relative to the partition start`() {
        partition.write(0, ByteArray(1024) { 7 }, blocks = 2)

        assertThat(disk.block(10).all { it == 7.toByte() }).isTrue()
        assertThat(disk.block(11).all { it == 7.toByte() }).isTrue()
        assertThat(disk.block(9).all { it == 0.toByte() }).isTrue()
        val read = ByteArray(600)
        partition.read(1, read, offset = 88, blocks = 1)
        assertThat(read[88]).isEqualTo(7.toByte())
        assertThat(partition.blockSize).isEqualTo(512)
    }

    @Test
    fun `access outside the partition is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { partition.read(80, ByteArray(512), blocks = 1) }
        assertThrows(IllegalArgumentException::class.java) { partition.write(79, ByteArray(1024), blocks = 2) }
        assertThrows(IllegalArgumentException::class.java) { partition.read(-1, ByteArray(512), blocks = 1) }
        assertThrows(IllegalArgumentException::class.java) { PartitionBlockDevice(disk, startLba = 50, blockCount = 51) }
    }
}
