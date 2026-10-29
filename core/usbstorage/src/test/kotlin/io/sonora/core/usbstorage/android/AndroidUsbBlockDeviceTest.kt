package io.sonora.core.usbstorage.android

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.usbstorage.protocol.BulkEndpoint
import io.sonora.core.usbstorage.protocol.BulkTransport
import io.sonora.core.usbstorage.protocol.ScsiBlockDevice
import io.sonora.core.usbstorage.protocol.SimulatedMassStorageDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidUsbBlockDeviceTest {

    private val stick = SimulatedMassStorageDevice(blockSize = 512, blockCount = 2048)
    private val transport = ControllableTransport(stick)
    private val releases = AtomicInteger()
    private var attached = true

    private fun open(connect: () -> BulkTransport = { transport }): AndroidUsbBlockDevice = AndroidUsbBlockDevice.open(
        deviceId = DEVICE,
        connect = connect,
        release = { releases.incrementAndGet() },
        isAttached = { attached },
        openScsi = { ScsiBlockDevice.open(it, sleep = {}) },
    )

    @Test
    fun `a transport failure on a detached device becomes device gone`() {
        val device = open()
        transport.broken = true
        attached = false

        val failure = assertThrows(UsbStorageException.DeviceGone::class.java) { device.read(0, ByteArray(512), 0, 1) }

        assertThat(failure.deviceId).isEqualTo(DEVICE)
        assertThat(failure).hasCauseThat().isInstanceOf(UsbStorageException.TransportFailure::class.java)
    }

    @Test
    fun `a transport failure on a device that is still attached stays a transport failure`() {
        val device = open()
        transport.broken = true

        assertThrows(UsbStorageException.TransportFailure::class.java) { device.write(0, ByteArray(512), 0, 1) }
    }

    @Test
    fun `calls after close fail with a closed-device error and touch nothing`() {
        val device = open()
        device.close()
        val commandsBefore = stick.cdbs.size

        val calls = listOf<() -> Unit>(
            { device.read(0, ByteArray(512), 0, 1) },
            { device.write(0, ByteArray(512), 0, 1) },
            { device.flush() },
        )

        calls.forEach { call ->
            val failure = assertThrows(UsbStorageException.DeviceClosed::class.java) { call() }
            assertThat(failure.deviceId).isEqualTo(DEVICE)
        }
        assertThat(stick.cdbs).hasSize(commandsBefore)
    }

    @Test
    fun `closing twice releases the interface once`() {
        val device = open()

        device.close()
        device.close()

        assertThat(releases.get()).isEqualTo(1)
    }

    @Test
    fun `close waits for a running command before releasing the interface`() {
        val device = open()
        val gate = CountDownLatch(1)
        transport.gate = gate
        val writeFailure = AtomicReference<Throwable?>()
        val writer = Thread {
            runCatching { device.write(5, ByteArray(512) { 7 }, 0, 1) }.onFailure { writeFailure.set(it) }
        }.apply { start() }
        assertThat(transport.entered.await(5, TimeUnit.SECONDS)).isTrue()

        val closer = Thread { device.close() }.apply { start() }
        awaitParked(closer)

        assertThat(closer.isAlive).isTrue()
        assertThat(releases.get()).isEqualTo(0)
        gate.countDown()
        writer.join(5_000)
        closer.join(5_000)
        assertThat(writeFailure.get()).isNull()
        assertThat(stick.block(5).all { it == 7.toByte() }).isTrue()
        assertThat(releases.get()).isEqualTo(1)
    }

    @Test
    fun `open releases the interface when the scsi device cannot be brought up`() {
        stick.peripheralDeviceType = 0x05 // a CD/DVD drive

        assertThrows(UsbStorageException.UnsupportedDevice::class.java) { open() }

        assertThat(releases.get()).isEqualTo(1)
    }

    @Test
    fun `open releases the interface and reports device gone when the stick is unplugged while opening`() {
        transport.broken = true
        attached = false

        assertThrows(UsbStorageException.DeviceGone::class.java) { open() }

        assertThat(releases.get()).isEqualTo(1)
    }

    @Test
    fun `open releases the interface when claiming it fails`() {
        assertThrows(UsbStorageException.TransportFailure::class.java) {
            open(connect = { throw UsbStorageException.TransportFailure("Could not claim the mass-storage interface") })
        }

        assertThat(releases.get()).isEqualTo(1)
    }

    @Test
    fun `open keeps the original failure when releasing the interface also fails`() {
        val releaseFailure = IllegalStateException("releaseInterface failed")
        transport.broken = true
        attached = false

        val failure = assertThrows(UsbStorageException.DeviceGone::class.java) {
            AndroidUsbBlockDevice.open(
                deviceId = DEVICE,
                connect = { transport },
                release = { throw releaseFailure },
                isAttached = { attached },
                openScsi = { ScsiBlockDevice.open(it, sleep = {}) },
            )
        }

        assertThat(failure).hasCauseThat().isInstanceOf(UsbStorageException.TransportFailure::class.java)
        assertThat(failure.suppressed.toList()).containsExactly(releaseFailure)
    }

    /** Waits until [thread] blocks (on a lock or a condition) or ends, without relying on a fixed delay. */
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state == Thread.State.RUNNABLE || thread.state == Thread.State.NEW) {
            check(System.nanoTime() < deadline) { "Thread never blocked" }
            Thread.onSpinWait()
        }
    }

    /** [SimulatedMassStorageDevice] whose link can break, and whose next bulk-out can be held at a [gate]. */
    private class ControllableTransport(private val stick: SimulatedMassStorageDevice) : BulkTransport by stick {
        @Volatile var broken = false

        @Volatile var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)

        override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            gate?.let {
                gate = null
                entered.countDown()
                it.await(5, TimeUnit.SECONDS)
            }
            return if (broken) -1 else stick.bulkOut(buffer, offset, length, timeoutMs)
        }

        override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
            if (broken) -1 else stick.bulkIn(buffer, offset, length, timeoutMs)

        override fun clearHalt(endpoint: BulkEndpoint): Boolean = !broken && stick.clearHalt(endpoint)

        override fun massStorageReset(): Boolean = !broken && stick.massStorageReset()
    }

    private companion object {
        const val DEVICE = "/dev/bus/usb/001/002"
    }
}
