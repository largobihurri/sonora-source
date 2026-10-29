package io.sonora.core.domain.storage

import kotlinx.coroutines.flow.Flow

/**
 * Raw access to USB mass-storage sticks attached to the phone (USB OTG), without root. Opening a device takes it away
 * from the system: Android unmounts it until the returned [UsbBlockDevice] is closed.
 *
 * Failures are [UsbStorageException]s, so a UI can explain each case.
 */
interface UsbStorageGateway {

    /** Attached mass-storage devices: the current list on collection, then a new list on every attach or detach. */
    val devices: Flow<List<UsbStorageDevice>>

    /**
     * Asks the user to let the app use [deviceId], showing the system dialog unless permission was already granted.
     * Throws [UsbStorageException.DeviceGone] when the device is not attached (or is detached while asking).
     */
    suspend fun requestPermission(deviceId: String): UsbPermissionResult

    /**
     * Claims [deviceId] and checks that it is a ready, supported block device. The caller owns the returned device and
     * must close it, so Android can mount the stick again.
     */
    suspend fun open(deviceId: String): UsbBlockDevice
}

/**
 * An attached USB device exposing a mass-storage interface. [id] identifies this attachment: it changes when the
 * stick is unplugged and plugged again. Names are what the device reports and may be missing.
 */
data class UsbStorageDevice(
    val id: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    /** False when the stick only speaks a protocol Sonora does not implement (e.g. USB Attached SCSI only). */
    val isSupported: Boolean,
)

enum class UsbPermissionResult { GRANTED, DENIED }

/** What the device reports about itself in its SCSI INQUIRY data, trimmed. */
data class StorageIdentity(val vendor: String, val product: String, val revision: String)

/**
 * An opened USB stick. Calls block: run them on an IO dispatcher. Errors are [UsbStorageException]s; a write on a
 * write-protected device fails with [UsbStorageException.WriteProtected] before anything is sent to it.
 * [close] gives the stick back to Android and is safe to call more than once; it waits for a running call to finish,
 * and later calls fail with [UsbStorageException.DeviceClosed].
 */
interface UsbBlockDevice : BlockDevice, AutoCloseable {
    val identity: StorageIdentity

    /** The medium reported itself as write-protected (e.g. a lock switch) when it was opened. */
    val isWriteProtected: Boolean

    val capacityBytes: Long get() = blockCount * blockSize

    override fun close()
}
