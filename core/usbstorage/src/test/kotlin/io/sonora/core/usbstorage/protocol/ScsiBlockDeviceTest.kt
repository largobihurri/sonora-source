package io.sonora.core.usbstorage.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbStorageException
import org.junit.Assert.assertThrows
import org.junit.Test

class ScsiBlockDeviceTest {

    private val sleeps = mutableListOf<Long>()
    private val logs = mutableListOf<String>()

    private fun open(device: SimulatedMassStorageDevice) =
        ScsiBlockDevice.open(device, sleep = { sleeps += it }, log = { logs += it })

    private fun SimulatedMassStorageDevice.count(opcode: Int) = opcodes().count { it == opcode }

    @Test
    fun `opening reads identity, capacity and write protection`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 2048)

        val device = open(stick)

        assertThat(device.identity).isEqualTo(StorageIdentity("SanDisk", "Cruzer Blade", "1.00"))
        assertThat(device.blockSize).isEqualTo(512)
        assertThat(device.blockCount).isEqualTo(2048)
        assertThat(device.isWriteProtected).isFalse()
        assertThat(stick.opcodes()).containsAtLeast(
            ScsiCommands.INQUIRY, ScsiCommands.TEST_UNIT_READY, ScsiCommands.READ_CAPACITY_10, ScsiCommands.MODE_SENSE_6,
        ).inOrder()
        assertThat(stick.lunsSeen).containsExactly(0)
    }

    @Test
    fun `a device that stalls get max lun is driven as logical unit 0`() {
        val stick = SimulatedMassStorageDevice().apply { reportedMaxLun = null }

        open(stick)

        assertThat(stick.lunsSeen).containsExactly(0)
    }

    @Test
    fun `read capacity 16 is used when read capacity 10 saturates`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 0x1_0000_0002L)

        val device = open(stick)

        assertThat(device.blockCount).isEqualTo(0x1_0000_0002L)
        assertThat(stick.count(ScsiCommands.SERVICE_ACTION_IN_16)).isEqualTo(1)
    }

    @Test
    fun `large reads and writes are split into bounded commands`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 4096)
        val device = open(stick)
        val data = ByteArray(300 * 512) { (it % 251).toByte() }

        device.write(lba = 10, buffer = data, blocks = 300)
        val readBack = ByteArray(data.size + 7)
        device.read(lba = 10, buffer = readBack, offset = 7, blocks = 300)

        val writes = stick.cdbs.filter { it[0] == ScsiCommands.WRITE_10.toByte() }
        assertThat(writes.map { (it[7].toInt() shl 8) or (it[8].toInt() and 0xFF) }).containsExactly(128, 128, 44).inOrder()
        assertThat(stick.count(ScsiCommands.READ_10)).isEqualTo(3)
        assertThat(readBack.copyOfRange(7, readBack.size)).isEqualTo(data)
        assertThat(stick.block(10 + 299)).isEqualTo(data.copyOfRange(299 * 512, 300 * 512))
    }

    @Test
    fun `reads and writes wait 30 seconds per transfer while setup keeps the short timeout`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 4096)
        val transport = TimeoutRecordingTransport(stick)
        val device = ScsiBlockDevice.open(transport, sleep = { sleeps += it }, log = { logs += it })
        assertThat(transport.timeouts.toSet()).containsExactly(BulkOnlyProtocol.DEFAULT_TIMEOUT_MS)

        transport.timeouts.clear()
        device.write(lba = 0, buffer = ByteArray(130 * 512), blocks = 130)
        device.read(lba = 0, buffer = ByteArray(130 * 512), blocks = 130)

        assertThat(transport.timeouts).isNotEmpty()
        assertThat(transport.timeouts.toSet()).containsExactly(30_000)
    }

    /** Records the timeout of every bulk transfer; slow SATA bridges need more than the 5 s that setup commands use. */
    private class TimeoutRecordingTransport(private val inner: BulkTransport) : BulkTransport by inner {
        val timeouts = mutableListOf<Int>()

        override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            timeouts += timeoutMs
            return inner.bulkOut(buffer, offset, length, timeoutMs)
        }

        override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            timeouts += timeoutMs
            return inner.bulkIn(buffer, offset, length, timeoutMs)
        }
    }

    @Test
    fun `4096 byte blocks are chunked by bytes, not blocks`() {
        val stick = SimulatedMassStorageDevice(blockSize = 4096, blockCount = 1024)
        val device = open(stick)

        device.write(lba = 0, buffer = ByteArray(40 * 4096) { 1 }, blocks = 40)

        assertThat(stick.count(ScsiCommands.WRITE_10)).isEqualTo(3)
        assertThat(ScsiBlockDevice.MAX_TRANSFER_BYTES / 4096).isEqualTo(16)
    }

    @Test
    fun `blocks past 32-bit LBAs use read and write 16`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 0x1_0000_0010L)
        val device = open(stick)
        val data = ByteArray(1024) { 5 }

        device.write(lba = 0xFFFFFFFFL, buffer = data, blocks = 2)
        val readBack = ByteArray(1024)
        device.read(lba = 0xFFFFFFFFL, buffer = readBack, blocks = 2)

        assertThat(stick.count(ScsiCommands.WRITE_10)).isEqualTo(1)
        assertThat(stick.count(ScsiCommands.WRITE_16)).isEqualTo(0)
        assertThat(readBack).isEqualTo(data)
        device.write(lba = 0x1_0000_0001L, buffer = data, blocks = 2)
        assertThat(stick.count(ScsiCommands.WRITE_16)).isEqualTo(1)
        assertThat(stick.block(0x1_0000_0002L)).isEqualTo(ByteArray(512) { 5 })
    }

    @Test
    fun `test unit ready is retried with backoff while the unit reports attention`() {
        val stick = SimulatedMassStorageDevice().apply { notReadyCount = 2 }

        open(stick)

        assertThat(stick.count(ScsiCommands.TEST_UNIT_READY)).isEqualTo(3)
        assertThat(stick.count(ScsiCommands.REQUEST_SENSE)).isEqualTo(2)
        assertThat(sleeps).hasSize(2)
        assertThat(sleeps[1]).isGreaterThan(sleeps[0])
    }

    @Test
    fun `test unit ready gives up after its bound`() {
        val stick = SimulatedMassStorageDevice().apply {
            notReadyCount = 100
            notReadySense = SenseData(SenseData.NOT_READY, 0x04, 0x01)
        }

        val failure = assertThrows(UsbStorageException.CommandFailed::class.java) { open(stick) }

        assertThat(failure.senseKey).isEqualTo(SenseData.NOT_READY)
        assertThat(failure.asc).isEqualTo(0x04)
        assertThat(stick.count(ScsiCommands.TEST_UNIT_READY)).isEqualTo(ScsiBlockDevice.TEST_UNIT_READY_ATTEMPTS)
    }

    @Test
    fun `a missing medium fails at once`() {
        val stick = SimulatedMassStorageDevice().apply {
            notReadyCount = 100
            notReadySense = SenseData(SenseData.NOT_READY, SenseData.ASC_MEDIUM_NOT_PRESENT, 0)
        }

        assertThrows(UsbStorageException.NoMedium::class.java) { open(stick) }
        assertThat(stick.count(ScsiCommands.TEST_UNIT_READY)).isEqualTo(1)
    }

    @Test
    fun `a device that is not a direct-access block device is unsupported`() {
        val stick = SimulatedMassStorageDevice().apply { peripheralDeviceType = 0x05 }

        assertThrows(UsbStorageException.UnsupportedDevice::class.java) { open(stick) }
    }

    @Test
    fun `a write-protected medium is reported and writes are refused before reaching it`() {
        val stick = SimulatedMassStorageDevice().apply { writeProtected = true }
        val device = open(stick)

        assertThat(device.isWriteProtected).isTrue()
        assertThrows(UsbStorageException.WriteProtected::class.java) {
            device.write(lba = 0, buffer = ByteArray(512), blocks = 1)
        }
        assertThat(stick.count(ScsiCommands.WRITE_10)).isEqualTo(0)
        device.read(lba = 0, buffer = ByteArray(512), blocks = 1)
    }

    @Test
    fun `a device that rejects mode sense is assumed writable and the rejection is logged`() {
        val stick = SimulatedMassStorageDevice().apply { modeSenseSupported = false }

        val device = open(stick)

        assertThat(device.isWriteProtected).isFalse()
        // The rejected data-in phase (a STALL) is logged by the protocol, then the fallback by the device.
        assertThat(logs).hasSize(2)
        assertThat(logs.first()).contains("Command 0x1A attempt 1/2: data-in")
        assertThat(logs.last()).contains("MODE SENSE")
        device.write(lba = 0, buffer = ByteArray(512) { 1 }, blocks = 1)
        assertThat(stick.block(0)).isEqualTo(ByteArray(512) { 1 })
    }

    @Test
    fun `a failed read reports the sense data from request sense`() {
        val stick = SimulatedMassStorageDevice().apply {
            failingReadLba = 3
            readFailureSense = SenseData(SenseData.MEDIUM_ERROR, 0x11, 0x04)
        }
        val device = open(stick)

        val failure = assertThrows(UsbStorageException.CommandFailed::class.java) {
            device.read(lba = 0, buffer = ByteArray(8 * 512), blocks = 8)
        }

        assertThat(listOf(failure.operationCode, failure.senseKey, failure.asc, failure.ascq))
            .containsExactly(ScsiCommands.READ_10, SenseData.MEDIUM_ERROR, 0x11, 0x04).inOrder()
    }

    @Test
    fun `flush issues synchronize cache`() {
        val stick = SimulatedMassStorageDevice()

        open(stick).flush()

        assertThat(stick.synchronizeCacheCount).isEqualTo(1)
    }

    @Test
    fun `flush tolerates devices without a cache command`() {
        val stick = SimulatedMassStorageDevice().apply {
            synchronizeCacheSense = SenseData(SenseData.ILLEGAL_REQUEST, 0x20, 0x00)
        }

        open(stick).flush()

        assertThat(stick.synchronizeCacheCount).isEqualTo(1)
    }

    @Test
    fun `flush reports other cache failures`() {
        val stick = SimulatedMassStorageDevice().apply {
            synchronizeCacheSense = SenseData(SenseData.MEDIUM_ERROR, 0x0C, 0x00)
        }

        val failure = assertThrows(UsbStorageException.CommandFailed::class.java) { open(stick).flush() }

        assertThat(failure.senseKey).isEqualTo(SenseData.MEDIUM_ERROR)
    }

    @Test
    fun `ranges outside the device or the buffer are rejected without commands`() {
        val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 100)
        val device = open(stick)
        val issued = stick.cdbs.size

        assertThrows(IllegalArgumentException::class.java) { device.read(100, ByteArray(512), blocks = 1) }
        assertThrows(IllegalArgumentException::class.java) { device.write(99, ByteArray(1024), blocks = 2) }
        assertThrows(IllegalArgumentException::class.java) { device.read(-1, ByteArray(512), blocks = 1) }
        assertThrows(IllegalArgumentException::class.java) { device.read(0, ByteArray(512), blocks = -1) }
        assertThrows(IllegalArgumentException::class.java) { device.read(0, ByteArray(1000), offset = 0, blocks = 2) }
        assertThrows(IllegalArgumentException::class.java) { device.write(0, ByteArray(512), offset = 1, blocks = 1) }
        device.read(0, ByteArray(0), blocks = 0)

        assertThat(stick.cdbs.size).isEqualTo(issued)
    }
}
