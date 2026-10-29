package io.sonora.core.usbstorage.android

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.sonora.core.usbstorage.protocol.BulkEndpoint
import io.sonora.core.usbstorage.protocol.BulkTransport

/**
 * [BulkTransport] over a claimed `UsbInterface`. `bulkTransfer` reports a STALL and any other failure alike as -1;
 * the protocol layer clears the halt and falls back to reset recovery. Blocking: call on an IO dispatcher.
 */
internal class AndroidBulkTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : BulkTransport {

    override val inMaxPacketSize: Int = inEndpoint.maxPacketSize

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(outEndpoint, buffer, offset, length, timeoutMs)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)

    /** CLEAR_FEATURE(ENDPOINT_HALT), USB 2.0 9.4.1: the SDK has no clear-halt call, so it goes as a control request. */
    override fun clearHalt(endpoint: BulkEndpoint): Boolean {
        val address = if (endpoint == BulkEndpoint.IN) inEndpoint.address else outEndpoint.address
        return connection.controlTransfer(
            REQUEST_TYPE_STANDARD_TO_ENDPOINT, REQUEST_CLEAR_FEATURE, FEATURE_ENDPOINT_HALT, address, null, 0, CONTROL_TIMEOUT_MS,
        ) >= 0
    }

    override fun massStorageReset(): Boolean = connection.controlTransfer(
        REQUEST_TYPE_CLASS_TO_INTERFACE, REQUEST_BULK_ONLY_RESET, 0, usbInterface.id, null, 0, CONTROL_TIMEOUT_MS,
    ) >= 0

    override fun getMaxLun(): Int? {
        val maxLun = ByteArray(1)
        val received = connection.controlTransfer(
            REQUEST_TYPE_CLASS_FROM_INTERFACE, REQUEST_GET_MAX_LUN, 0, usbInterface.id, maxLun, 1, CONTROL_TIMEOUT_MS,
        )
        return if (received == 1) maxLun[0].toInt() and 0x0F else null
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 5_000
        const val REQUEST_TYPE_STANDARD_TO_ENDPOINT = 0x02
        const val REQUEST_CLEAR_FEATURE = 0x01
        const val FEATURE_ENDPOINT_HALT = 0x00
        const val REQUEST_TYPE_CLASS_TO_INTERFACE = 0x21
        const val REQUEST_TYPE_CLASS_FROM_INTERFACE = 0xA1
        const val REQUEST_BULK_ONLY_RESET = 0xFF
        const val REQUEST_GET_MAX_LUN = 0xFE
    }
}
