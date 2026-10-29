package io.sonora.core.domain.storage

import java.io.IOException

/**
 * Why a USB stick could not be used. These are [IOException]s so they travel through [BlockDevice] callers (such as
 * the formatter) unchanged; a UI matches on the subtype to explain the problem.
 */
sealed class UsbStorageException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The user did not grant access to the device. */
    class PermissionDenied(val deviceId: String) : UsbStorageException("No permission to use USB device $deviceId")

    /** The device was unplugged, or stopped answering, before or during the operation. */
    class DeviceGone(val deviceId: String, cause: Throwable? = null) :
        UsbStorageException("USB device $deviceId is no longer attached", cause)

    /** The device was used after [UsbBlockDevice.close]: the app gave it back to Android. */
    class DeviceClosed(val deviceId: String) : UsbStorageException("USB device $deviceId is closed")

    /** The device is not a mass-storage stick Sonora can drive (e.g. no Bulk-Only interface, or a CD drive). */
    class UnsupportedDevice(val reason: String) : UsbStorageException("Unsupported USB device: $reason")

    /** A card reader or drive without a medium inserted. */
    class NoMedium : UsbStorageException("No medium in the USB device")

    /** The medium refuses writes (lock switch or a worn-out stick in read-only mode). */
    class WriteProtected : UsbStorageException("The USB device is write-protected")

    /** The USB link failed: a bulk transfer, a malformed status, or a phase error that reset recovery did not cure. */
    class TransportFailure(message: String, cause: Throwable? = null) : UsbStorageException(message, cause)

    /** The device rejected a SCSI command; [senseKey], [asc] and [ascq] come from its sense data (SPC-4 4.5). */
    class CommandFailed(val operationCode: Int, val senseKey: Int, val asc: Int, val ascq: Int) :
        UsbStorageException(
            "SCSI command 0x%02X failed: sense key 0x%X, ASC 0x%02X, ASCQ 0x%02X".format(operationCode, senseKey, asc, ascq),
        )
}
