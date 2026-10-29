package io.sonora.core.domain.storage

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class UsbStorageContractTest {

    @Test
    fun `capacity is computed in 64 bits for sticks larger than 2 GiB`() {
        val device = FixedUsbBlockDevice(blockSize = 512, blockCount = 0x1_0000_0000L)

        assertThat(device.capacityBytes).isEqualTo(2_199_023_255_552L)
    }

    @Test
    fun `usb failures are IOExceptions so block device callers report them as device errors`() {
        val failures: List<UsbStorageException> = listOf(
            UsbStorageException.PermissionDenied("dev"),
            UsbStorageException.DeviceGone("dev"),
            UsbStorageException.DeviceClosed("dev"),
            UsbStorageException.UnsupportedDevice("UAS only"),
            UsbStorageException.NoMedium(),
            UsbStorageException.WriteProtected(),
            UsbStorageException.TransportFailure("phase error"),
            UsbStorageException.CommandFailed(0x2A, 0x3, 0x0C, 0x00),
        )

        failures.forEach { assertThat(it).isInstanceOf(IOException::class.java) }
    }

    @Test
    fun `a failed command keeps its sense data and names it in the message`() {
        val failure = UsbStorageException.CommandFailed(operationCode = 0x2A, senseKey = 0x3, asc = 0x0C, ascq = 0x02)

        assertThat(failure.senseKey).isEqualTo(3)
        assertThat(failure.asc).isEqualTo(0x0C)
        assertThat(failure.ascq).isEqualTo(2)
        assertThat(failure.message).isEqualTo("SCSI command 0x2A failed: sense key 0x3, ASC 0x0C, ASCQ 0x02")
    }

    private class FixedUsbBlockDevice(override val blockSize: Int, override val blockCount: Long) : UsbBlockDevice {
        override val identity = StorageIdentity("V", "P", "1")
        override val isWriteProtected = false
        override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = Unit
        override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
