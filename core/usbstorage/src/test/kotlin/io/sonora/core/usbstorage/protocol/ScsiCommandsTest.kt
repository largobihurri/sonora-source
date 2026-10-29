package io.sonora.core.usbstorage.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbStorageException
import org.junit.Test

class ScsiCommandsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `read and write 10 encode a big-endian LBA and block count`() {
        assertThat(ScsiCommands.read(0x01020304, 0x0506)).isEqualTo(bytes(0x28, 0, 1, 2, 3, 4, 0, 5, 6, 0))
        assertThat(ScsiCommands.write(0xFFFFFFFFL, 1)).isEqualTo(bytes(0x2A, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 1, 0))
    }

    @Test
    fun `read and write switch to 16 when the LBA needs more than 32 bits`() {
        assertThat(ScsiCommands.read(0x1_0000_0000L, 128))
            .isEqualTo(bytes(0x88, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0x80, 0, 0))
        assertThat(ScsiCommands.write(0x0102_0304_0506L, 2))
            .isEqualTo(bytes(0x8A, 0, 0, 0, 1, 2, 3, 4, 5, 6, 0, 0, 0, 2, 0, 0))
    }

    @Test
    fun `read switches to 16 when the block count needs more than 16 bits`() {
        assertThat(ScsiCommands.read(0, 0x10000)[0]).isEqualTo(0x88.toByte())
    }

    @Test
    fun `fixed command blocks follow SPC and SBC`() {
        assertThat(ScsiCommands.testUnitReady()).isEqualTo(ByteArray(6))
        assertThat(ScsiCommands.requestSense()).isEqualTo(bytes(0x03, 0, 0, 0, 18, 0))
        assertThat(ScsiCommands.inquiry()).isEqualTo(bytes(0x12, 0, 0, 0, 36, 0))
        assertThat(ScsiCommands.modeSense6AllPages()).isEqualTo(bytes(0x1A, 0, 0x3F, 0, 192, 0))
        assertThat(ScsiCommands.readCapacity10()).isEqualTo(bytes(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertThat(ScsiCommands.readCapacity16()).isEqualTo(bytes(0x9E, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0))
        assertThat(ScsiCommands.synchronizeCache10()).isEqualTo(bytes(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `fixed and descriptor format sense data are decoded`() {
        val fixed = ByteArray(18).also { it[0] = 0xF0.toByte(); it[2] = 0x26; it[12] = 0x3A; it[13] = 0x01 }
        val descriptor = bytes(0x72, 0x06, 0x28, 0x00, 0, 0, 0, 0)

        assertThat(ScsiCommands.parseSense(fixed, 18)).isEqualTo(SenseData(0x6, 0x3A, 0x01))
        assertThat(ScsiCommands.parseSense(descriptor, 8)).isEqualTo(SenseData(0x6, 0x28, 0x00))
        assertThat(ScsiCommands.parseSense(ByteArray(18), 18)).isNull()
        assertThat(ScsiCommands.parseSense(fixed, 2)).isNull()
    }

    @Test
    fun `sense data maps to typed failures`() {
        assertThat(SenseData(0x2, 0x3A, 0).toException(0x00)).isInstanceOf(UsbStorageException.NoMedium::class.java)
        assertThat(SenseData(0x7, 0x27, 0).toException(0x2A)).isInstanceOf(UsbStorageException.WriteProtected::class.java)
        val failed = SenseData(0x3, 0x11, 0x02).toException(0x28) as UsbStorageException.CommandFailed
        assertThat(listOf(failed.operationCode, failed.senseKey, failed.asc, failed.ascq)).containsExactly(0x28, 3, 0x11, 2)
    }

    @Test
    fun `inquiry data yields the device type and trimmed identity`() {
        val data = ByteArray(36).also {
            it[0] = 0x05
            it[1] = 0x80.toByte()
            "Kingston".toByteArray().copyInto(it, 8)
            "DataTraveler 3.0".toByteArray().copyInto(it, 16)
            "PMAP".toByteArray().copyInto(it, 32)
        }

        assertThat(ScsiCommands.parseInquiry(data, 36))
            .isEqualTo(InquiryData(5, removable = true, StorageIdentity("Kingston", "DataTraveler 3.0", "PMAP")))
        assertThat(ScsiCommands.parseInquiry(data, 20).identity).isEqualTo(StorageIdentity("Kingston", "Data", ""))
    }

    @Test
    fun `read capacity responses are decoded as unsigned values`() {
        assertThat(ScsiCommands.parseCapacity10(bytes(0xFF, 0xFF, 0xFF, 0xFE, 0, 0, 0x10, 0)))
            .isEqualTo(Capacity(lastLba = 0xFFFFFFFEL, blockSize = 4096))
        val sixteen = ByteArray(32).also { bytes(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 2, 0).copyInto(it) }
        assertThat(ScsiCommands.parseCapacity16(sixteen)).isEqualTo(Capacity(lastLba = 0x1_0000_0000L, blockSize = 512))
    }

    @Test
    fun `the write protect bit is bit 7 of the mode parameter header device-specific byte`() {
        assertThat(ScsiCommands.isWriteProtected(bytes(3, 0, 0x80, 0), 4)).isTrue()
        assertThat(ScsiCommands.isWriteProtected(bytes(3, 0, 0x10, 0), 4)).isFalse()
        assertThat(ScsiCommands.isWriteProtected(bytes(3, 0), 2)).isFalse()
    }
}
