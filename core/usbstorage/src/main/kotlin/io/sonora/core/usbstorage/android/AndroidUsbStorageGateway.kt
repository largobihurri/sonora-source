package io.sonora.core.usbstorage.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.storage.UsbBlockDevice
import io.sonora.core.domain.storage.UsbPermissionResult
import io.sonora.core.domain.storage.UsbStorageDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.domain.storage.UsbStorageGateway
import io.sonora.core.usbstorage.di.UsbStorageDispatcher
import io.sonora.core.usbstorage.protocol.ScsiBlockDevice
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [UsbStorageGateway] over the Android USB Host API. Opening claims the mass-storage interface with `force = true`,
 * which detaches the kernel driver (Android unmounts the stick) until the device is closed.
 */
@Singleton
class AndroidUsbStorageGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    @UsbStorageDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UsbStorageGateway {

    /** Null on devices without USB host support: they never list a stick. */
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val permissionRequests = AtomicInteger()

    override val devices: Flow<List<UsbStorageDevice>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(attachedStorageDevices())
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        send(attachedStorageDevices())
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate().distinctUntilChanged()

    override suspend fun requestPermission(deviceId: String): UsbPermissionResult {
        val manager = usbManager ?: throw UsbStorageException.DeviceGone(deviceId)
        val device = manager.deviceList[deviceId] ?: throw UsbStorageException.DeviceGone(deviceId)
        if (manager.hasPermission(device)) return UsbPermissionResult.GRANTED

        val requestCode = permissionRequests.incrementAndGet()
        val action = "${context.packageName}.usbstorage.USB_PERMISSION.$requestCode"
        return suspendCancellableCoroutine { continuation ->
            val registered = AtomicBoolean(true)
            lateinit var receiver: BroadcastReceiver
            fun unregister() {
                if (registered.compareAndSet(true, false)) context.unregisterReceiver(receiver)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        // The action is unique to this request, so its result needs no device check.
                        action -> {
                            unregister()
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                                manager.hasPermission(device)
                            if (continuation.isActive) {
                                continuation.resume(if (granted) UsbPermissionResult.GRANTED else UsbPermissionResult.DENIED)
                            }
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            if (intent.usbDevice()?.deviceName != deviceId) return
                            unregister()
                            if (continuation.isActive) continuation.resumeWithException(UsbStorageException.DeviceGone(deviceId))
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(action)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            // The result comes from our own PendingIntent, so a non-exported receiver gets it.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { unregister() }

            // Mutable so the system can add EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED; explicit (package set)
            // because Android 14 rejects mutable PendingIntents with implicit intents.
            val intent = Intent(action).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(context, requestCode, intent, flags))
        }
    }

    override suspend fun open(deviceId: String): UsbBlockDevice = withContext(ioDispatcher) {
        val manager = usbManager ?: throw UsbStorageException.DeviceGone(deviceId)
        val device = manager.deviceList[deviceId] ?: throw UsbStorageException.DeviceGone(deviceId)
        if (!manager.hasPermission(device)) throw UsbStorageException.PermissionDenied(deviceId)
        val selection = when (val selected = MassStorageInterfaces.select(device.interfaceDescriptors())) {
            is InterfaceSelection.BulkOnly -> selected
            is InterfaceSelection.Unsupported -> throw UsbStorageException.UnsupportedDevice(selected.reason)
            InterfaceSelection.NotMassStorage -> throw UsbStorageException.UnsupportedDevice("not a mass-storage device")
        }
        val usbInterface = device.getInterface(selection.usbInterface.index)
        val connection = try {
            manager.openDevice(device)
        } catch (denied: SecurityException) {
            Log.w(TAG, "$deviceId: openDevice refused", denied)
            throw UsbStorageException.PermissionDenied(deviceId)
        } ?: throw UsbStorageException.DeviceGone(deviceId)

        AndroidUsbBlockDevice.open(
            deviceId,
            connect = {
                if (!connection.claimInterface(usbInterface, true)) {
                    throw UsbStorageException.TransportFailure("Could not claim the mass-storage interface")
                }
                if (usbInterface.alternateSetting != 0 && !connection.setInterface(usbInterface)) {
                    throw UsbStorageException.TransportFailure("Could not select the Bulk-Only alternate setting")
                }
                AndroidBulkTransport(
                    connection,
                    usbInterface,
                    inEndpoint = usbInterface.getEndpoint(selection.bulkIn.index),
                    outEndpoint = usbInterface.getEndpoint(selection.bulkOut.index),
                )
            },
            release = {
                connection.releaseInterface(usbInterface)
                connection.close()
            },
            isAttached = { isAttached(deviceId) },
            openScsi = { transport -> ScsiBlockDevice.open(transport, log = { Log.w(TAG, "$deviceId: $it") }) },
        )
    }

    private fun isAttached(deviceId: String): Boolean = usbManager?.deviceList?.containsKey(deviceId) == true

    private fun attachedStorageDevices(): List<UsbStorageDevice> =
        usbManager?.deviceList?.values.orEmpty().mapNotNull { it.toStorageDevice() }.sortedBy { it.id }

    private fun UsbDevice.toStorageDevice(): UsbStorageDevice? {
        val selection = MassStorageInterfaces.select(interfaceDescriptors())
        if (selection == InterfaceSelection.NotMassStorage) return null
        return UsbStorageDevice(
            id = deviceName,
            vendorId = vendorId,
            productId = productId,
            manufacturerName = manufacturerName,
            productName = productName,
            isSupported = selection is InterfaceSelection.BulkOnly,
        )
    }

    private fun Intent.usbDevice(): UsbDevice? =
        IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

    private companion object {
        const val TAG = "SonoraUsbStorage"
    }
}

internal fun UsbDevice.interfaceDescriptors(): List<InterfaceDescriptor> = (0 until interfaceCount).map { index ->
    val usbInterface = getInterface(index)
    InterfaceDescriptor(
        index = index,
        id = usbInterface.id,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            EndpointDescriptor(endpointIndex, endpoint.address, endpoint.type, endpoint.maxPacketSize)
        },
    )
}
