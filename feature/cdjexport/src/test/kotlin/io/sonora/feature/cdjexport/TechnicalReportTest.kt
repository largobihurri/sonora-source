package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbStorageException
import java.io.IOException
import org.junit.Test

class TechnicalReportTest {

    private val environment = AppEnvironment(
        appVersion = "0.1.0 (1)",
        androidVersion = "16",
        sdkInt = 36,
        deviceModel = "realme RMX5011",
    )

    @Test
    fun `a format failure lists the phone, the stick and the whole exception chain`() {
        val transport = UsbStorageException.TransportFailure(
            "Command 0x2A failed after 2 attempts: attempt 1: CBW: bulkTransfer returned -1 (0 of 31 bytes transferred)",
        )
        val gone = UsbStorageException.DeviceGone("/dev/bus/usb/001/002", transport)

        val report = technicalReport(
            environment,
            FailureReport(
                step = "Formatting",
                usbId = "0781:5581",
                usbName = "SanDisk 3.2Gen1",
                identity = StorageIdentity("SanDisk", "3.2Gen1", "1.00"),
                capacityBytes = 123_060_879_360,
                blockSize = 512,
                failure = "IoError",
                lba = 0,
                cause = gone,
            ),
        )

        assertThat(report).isEqualTo(
            """
            Sonora technical details
            App version: 0.1.0 (1)
            Android: 16 (SDK 36)
            Phone: realme RMX5011
            Step: Formatting
            USB device: 0781:5581 (SanDisk 3.2Gen1)
            INQUIRY: vendor "SanDisk", product "3.2Gen1", revision "1.00"
            Capacity: 123,060,879,360 bytes (240,353,280 blocks of 512 bytes)
            Failure: IoError
            LBA: 0
            Exception chain:
              io.sonora.core.domain.storage.UsbStorageException${'$'}DeviceGone: USB device /dev/bus/usb/001/002 is no longer attached
              caused by io.sonora.core.domain.storage.UsbStorageException${'$'}TransportFailure: Command 0x2A failed after 2 attempts: attempt 1: CBW: bulkTransfer returned -1 (0 of 31 bytes transferred)
            """.trimIndent(),
        )
    }

    @Test
    fun `a stick that never opened reports unknown details and suppressed failures`() {
        val failure = UsbStorageException.TransportFailure("Could not claim the mass-storage interface")
        failure.addSuppressed(IllegalStateException("release failed"))

        val report = technicalReport(
            environment,
            FailureReport(
                step = "Opening the stick",
                usbId = "0781:5581",
                usbName = "SanDisk 3.2Gen1",
                identity = null,
                capacityBytes = null,
                blockSize = null,
                failure = "TransportFailure",
                lba = null,
                cause = failure,
            ),
        )

        assertThat(report).contains("INQUIRY: unknown\nCapacity: unknown\nFailure: TransportFailure\nLBA: n/a\n")
        assertThat(report).endsWith(
            "Exception chain:\n" +
                "  io.sonora.core.domain.storage.UsbStorageException\$TransportFailure: Could not claim the " +
                "mass-storage interface\n" +
                "    suppressed java.lang.IllegalStateException: release failed",
        )
    }

    @Test
    fun `a retried format also reports the first attempt's failure`() {
        val first = UsbStorageException.TransportFailure("first write")
        val second = UsbStorageException.TransportFailure("second write")

        val report = technicalReport(
            environment,
            FailureReport(
                step = "Formatting",
                usbId = null,
                usbName = null,
                identity = null,
                capacityBytes = null,
                blockSize = null,
                failure = "IoError",
                lba = 0,
                cause = second,
                retriedAfter = first,
            ),
        )

        assertThat(report).contains("USB device: unknown\n")
        assertThat(report).endsWith(
            "Exception chain:\n" +
                "  io.sonora.core.domain.storage.UsbStorageException\$TransportFailure: second write\n" +
                "Retried once after re-opening the stick; the first attempt failed with:\n" +
                "  io.sonora.core.domain.storage.UsbStorageException\$TransportFailure: first write",
        )
    }

    @Test
    fun `a cyclic cause chain is listed once and a missing message is named`() {
        val outer = IOException("outer")
        val inner = IOException()
        outer.initCause(inner)
        inner.initCause(outer)

        val report = technicalReport(
            environment,
            FailureReport("Formatting", null, null, null, null, null, "Unexpected exception", null, outer),
        )

        assertThat(report).endsWith(
            "Exception chain:\n" +
                "  java.io.IOException: outer\n" +
                "  caused by java.io.IOException: (no message)",
        )
    }

    @Test
    fun `a failure without an exception says so`() {
        val report = technicalReport(
            environment,
            FailureReport("Formatting", null, null, null, null, null, "VerificationMismatch", 2048, cause = null),
        )

        assertThat(report).endsWith("LBA: 2048\nException chain: none")
    }
}
