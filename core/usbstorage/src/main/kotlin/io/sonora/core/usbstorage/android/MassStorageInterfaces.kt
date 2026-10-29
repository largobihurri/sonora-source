package io.sonora.core.usbstorage.android

// Values of android.hardware.usb.UsbConstants, repeated so this file needs no Android classes.
private const val USB_CLASS_MASS_STORAGE = 0x08
private const val USB_ENDPOINT_XFER_BULK = 2
private const val USB_DIR_IN = 0x80

/** An endpoint as `UsbEndpoint` describes it; [index] is its position in `UsbInterface.getEndpoint`. */
data class EndpointDescriptor(val index: Int, val address: Int, val type: Int, val maxPacketSize: Int) {
    val isBulk: Boolean get() = type == USB_ENDPOINT_XFER_BULK
    val isIn: Boolean get() = (address and USB_DIR_IN) != 0
}

/** An interface as `UsbInterface` describes it; [index] is its position in `UsbDevice.getInterface`. */
data class InterfaceDescriptor(
    val index: Int,
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<EndpointDescriptor>,
) {
    val isMassStorage: Boolean get() = interfaceClass == USB_CLASS_MASS_STORAGE
}

sealed interface InterfaceSelection {
    data class BulkOnly(
        val usbInterface: InterfaceDescriptor,
        val bulkIn: EndpointDescriptor,
        val bulkOut: EndpointDescriptor,
    ) : InterfaceSelection

    /** A mass-storage device Sonora cannot drive, and why. */
    data class Unsupported(val reason: String) : InterfaceSelection

    data object NotMassStorage : InterfaceSelection
}

/**
 * Picks the interface Sonora drives: mass storage (0x08), SCSI transparent command set (0x06), Bulk-Only Transport
 * (0x50), with one bulk IN and one bulk OUT endpoint. Kept free of Android types so it is tested on the JVM.
 */
object MassStorageInterfaces {
    private const val SUBCLASS_SCSI_TRANSPARENT = 0x06
    private const val PROTOCOL_BULK_ONLY = 0x50

    fun select(interfaces: List<InterfaceDescriptor>): InterfaceSelection {
        val massStorage = interfaces.filter { it.isMassStorage }
        if (massStorage.isEmpty()) return InterfaceSelection.NotMassStorage
        val bulkOnly = massStorage.filter {
            it.interfaceSubclass == SUBCLASS_SCSI_TRANSPARENT && it.interfaceProtocol == PROTOCOL_BULK_ONLY
        }
        if (bulkOnly.isEmpty()) {
            val offered = massStorage.joinToString { "subclass 0x%02X protocol 0x%02X".format(it.interfaceSubclass, it.interfaceProtocol) }
            return InterfaceSelection.Unsupported("no SCSI Bulk-Only interface ($offered)")
        }
        for (candidate in bulkOnly) {
            val bulkIn = candidate.endpoints.firstOrNull { it.isBulk && it.isIn }
            val bulkOut = candidate.endpoints.firstOrNull { it.isBulk && !it.isIn }
            if (bulkIn != null && bulkOut != null) return InterfaceSelection.BulkOnly(candidate, bulkIn, bulkOut)
        }
        return InterfaceSelection.Unsupported("Bulk-Only interface without a bulk IN and a bulk OUT endpoint")
    }
}
