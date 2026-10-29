package io.sonora.core.usbstorage.android

import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbBlockDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.usbstorage.protocol.BulkTransport
import io.sonora.core.usbstorage.protocol.ScsiBlockDevice
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An opened stick: [ScsiBlockDevice] over a claimed interface. A transport failure on a device that is no longer
 * attached becomes [UsbStorageException.DeviceGone]. [close] runs [release], which gives the interface back so the
 * kernel driver can bind again and Android can remount the stick. Calls and [close] are serialised: closing waits for
 * a running command, so the interface is never released under a bulk transfer, and later calls fail with
 * [UsbStorageException.DeviceClosed]. Blocking: call on an IO dispatcher.
 */
internal class AndroidUsbBlockDevice(
    private val deviceId: String,
    private val scsi: ScsiBlockDevice,
    private val release: () -> Unit,
    private val isAttached: () -> Boolean,
) : UsbBlockDevice {

    private val lock = ReentrantLock()
    private var closed = false // guarded by lock

    override val blockSize: Int get() = scsi.blockSize
    override val blockCount: Long get() = scsi.blockCount
    override val identity: StorageIdentity get() = scsi.identity
    override val isWriteProtected: Boolean get() = scsi.isWriteProtected

    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) =
        whileOpen { scsi.read(lba, buffer, offset, blocks) }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) =
        whileOpen { scsi.write(lba, buffer, offset, blocks) }

    override fun flush() = whileOpen { scsi.flush() }

    override fun close() = lock.withLock {
        if (!closed) {
            closed = true
            release()
        }
    }

    private inline fun whileOpen(operation: () -> Unit) = lock.withLock {
        if (closed) throw UsbStorageException.DeviceClosed(deviceId)
        try {
            operation()
        } catch (failure: UsbStorageException.TransportFailure) {
            throw if (isAttached()) failure else UsbStorageException.DeviceGone(deviceId, failure)
        }
    }

    companion object {
        /**
         * Brings up [deviceId] over the transport [connect] returns after claiming the interface. When claiming or
         * [openScsi] fails, [release] gives the interface back before the failure propagates (a failing [release] is
         * attached to it as suppressed, never replacing it); a transport failure on a device that is no longer attached
         * becomes [UsbStorageException.DeviceGone].
         */
        fun open(
            deviceId: String,
            connect: () -> BulkTransport,
            release: () -> Unit,
            isAttached: () -> Boolean,
            openScsi: (BulkTransport) -> ScsiBlockDevice,
        ): AndroidUsbBlockDevice {
            try {
                return AndroidUsbBlockDevice(deviceId, openScsi(connect()), release, isAttached)
            } catch (failure: Exception) {
                val releaseFailure = try {
                    release()
                    null
                } catch (releaseFailure: Exception) {
                    releaseFailure
                }
                val reported = if (failure is UsbStorageException.TransportFailure && !isAttached()) {
                    UsbStorageException.DeviceGone(deviceId, failure)
                } else {
                    failure
                }
                releaseFailure?.let(reported::addSuppressed)
                throw reported
            }
        }
    }
}
