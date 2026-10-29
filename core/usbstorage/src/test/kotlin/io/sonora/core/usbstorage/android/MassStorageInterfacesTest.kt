package io.sonora.core.usbstorage.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MassStorageInterfacesTest {

    private val bulkIn = EndpointDescriptor(index = 0, address = 0x81, type = BULK, maxPacketSize = 512)
    private val bulkOut = EndpointDescriptor(index = 1, address = 0x02, type = BULK, maxPacketSize = 512)
    private val interruptIn = EndpointDescriptor(index = 0, address = 0x83, type = INTERRUPT, maxPacketSize = 8)

    private fun massStorage(index: Int, protocol: Int, vararg endpoints: EndpointDescriptor) =
        InterfaceDescriptor(index, id = index, interfaceClass = 0x08, interfaceSubclass = 0x06, protocol, endpoints.toList())

    @Test
    fun `the bulk-only scsi interface and its bulk endpoints are selected`() {
        val hid = InterfaceDescriptor(0, 0, interfaceClass = 0x03, interfaceSubclass = 0, interfaceProtocol = 0, listOf(interruptIn))
        val storage = massStorage(1, 0x50, bulkOut, bulkIn)

        val selection = MassStorageInterfaces.select(listOf(hid, storage))

        assertThat(selection).isEqualTo(InterfaceSelection.BulkOnly(storage, bulkIn = bulkIn, bulkOut = bulkOut))
    }

    @Test
    fun `bulk-only is preferred when a device also offers usb attached scsi`() {
        val uas = massStorage(0, 0x62, bulkIn, bulkOut)
        val bot = massStorage(1, 0x50, bulkIn, bulkOut)

        val selection = MassStorageInterfaces.select(listOf(uas, bot))

        assertThat((selection as InterfaceSelection.BulkOnly).usbInterface).isEqualTo(bot)
    }

    @Test
    fun `a device that only speaks usb attached scsi is unsupported`() {
        val selection = MassStorageInterfaces.select(listOf(massStorage(0, 0x62, bulkIn, bulkOut)))

        assertThat(selection).isInstanceOf(InterfaceSelection.Unsupported::class.java)
    }

    @Test
    fun `a bulk-only interface without both bulk endpoints is unsupported`() {
        val selection = MassStorageInterfaces.select(listOf(massStorage(0, 0x50, bulkIn, interruptIn)))

        assertThat(selection).isInstanceOf(InterfaceSelection.Unsupported::class.java)
    }

    @Test
    fun `a device without a mass-storage interface is not mass storage`() {
        val hid = InterfaceDescriptor(0, 0, interfaceClass = 0x03, interfaceSubclass = 0, interfaceProtocol = 0, listOf(interruptIn))

        assertThat(MassStorageInterfaces.select(listOf(hid))).isEqualTo(InterfaceSelection.NotMassStorage)
    }

    private companion object {
        const val BULK = 2
        const val INTERRUPT = 3
    }
}
