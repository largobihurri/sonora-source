package io.sonora.core.fat

import io.sonora.core.domain.storage.BlockDevice

/** The [blockCount] blocks of [device] from [startLba] on, addressed from 0; access outside them is rejected. */
class PartitionBlockDevice(
    private val device: BlockDevice,
    val startLba: Long,
    override val blockCount: Long,
) : BlockDevice {

    init {
        require(startLba >= 0 && blockCount >= 0 && startLba + blockCount <= device.blockCount) {
            "Partition $startLba + $blockCount does not fit a device of ${device.blockCount} blocks"
        }
    }

    override val blockSize: Int get() = device.blockSize

    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        checkRange(lba, blocks)
        device.read(startLba + lba, buffer, offset, blocks)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        checkRange(lba, blocks)
        device.write(startLba + lba, buffer, offset, blocks)
    }

    override fun flush() = device.flush()

    private fun checkRange(lba: Long, blocks: Int) =
        require(lba >= 0 && blocks >= 0 && lba + blocks <= blockCount) { "Blocks $lba + $blocks outside $blockCount" }
}
