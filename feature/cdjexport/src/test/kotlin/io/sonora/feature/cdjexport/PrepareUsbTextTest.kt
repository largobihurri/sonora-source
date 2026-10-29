package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.FormatStructure
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.UsbStorageDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.domain.storage.VolumeLabel
import io.sonora.core.domain.storage.VolumeLabelError
import java.io.IOException
import java.util.Locale
import org.junit.Test

class PrepareUsbTextTest {

    private val en = XmlStrings.of()

    @Test
    fun `capacity is decimal with one decimal like the size printed on a stick`() {
        assertThat(formatCapacity(0, Locale.US)).isEqualTo("0 B")
        assertThat(formatCapacity(999, Locale.US)).isEqualTo("999 B")
        assertThat(formatCapacity(1_000, Locale.US)).isEqualTo("1.0 kB")
        assertThat(formatCapacity(67_108_864, Locale.US)).isEqualTo("67.1 MB")
        assertThat(formatCapacity(999_999_999, Locale.US)).isEqualTo("1.0 GB")
        assertThat(formatCapacity(7_747_397_632, Locale.US)).isEqualTo("7.7 GB")
        assertThat(formatCapacity(16_008_609_792, Locale.US)).isEqualTo("16.0 GB")
        assertThat(formatCapacity(2_000_398_934_016, Locale.US)).isEqualTo("2.0 TB")
    }

    @Test
    fun `exact size groups digits`() {
        assertThat(formatExactSize(16_008_609_792, en)).isEqualTo("16,008,609,792 bytes")
        assertThat(formatExactSize(512, en)).isEqualTo("512 bytes")
    }

    @Test
    fun `capacity and exact size follow the language`() {
        val es = XmlStrings.of("-es")
        val eu = XmlStrings.of("-eu")

        assertThat(formatCapacity(16_008_609_792, es.locale)).isEqualTo("16,0 GB")
        assertThat(formatExactSize(16_008_609_792, es)).isEqualTo("16.008.609.792 bytes")
        assertThat(formatExactSize(16_008_609_792, eu)).isEqualTo("16.008.609.792 byte")
    }

    @Test
    fun `device names come from the usb descriptors without repeating the maker`() {
        val stick = UsbStorageDevice("/dev/bus/usb/001/002", 0x0781, 0x5567, "SanDisk", "Cruzer Blade", isSupported = true)

        assertThat(stick.toItem()).isEqualTo(DeviceItem("/dev/bus/usb/001/002", "SanDisk Cruzer Blade", "0781:5567", isSupported = true))
        assertThat(stick.toItem().detail(en)).isEqualTo("USB 0781:5567")
        assertThat(stick.copy(productName = "SanDisk Ultra").toItem().name).isEqualTo("SanDisk Ultra")
        val nameless = stick.copy(manufacturerName = null, productName = " ").toItem()
        assertThat(nameless.name).isNull()
        assertThat(nameless.displayName(en)).isEqualTo("USB storage device")
        assertThat(nameless.displayName(XmlStrings.of("-es"))).isEqualTo("Dispositivo de almacenamiento USB")
    }

    @Test
    fun `an unsupported device explains why it cannot be selected`() {
        val uas = UsbStorageDevice("d", 0x174c, 0x55aa, "ASMedia", "SSD", isSupported = false).toItem()

        assertThat(uas.isSelectable).isFalse()
        assertThat(uas.detail(en)).contains("not supported")
    }

    @Test
    fun `label errors explain how to fix the label`() {
        assertThat(VolumeLabelError.Empty.message(en)).isEqualTo("Enter a label of 1 to 11 characters.")
        assertThat((VolumeLabel.validate("PIONEER MUSIC")!!).message(en))
            .isEqualTo("The label has 13 characters; FAT allows at most 11.")
        assertThat((VolumeLabel.validate("A/B")!!).message(en)).isEqualTo("\"/\" is not allowed in a FAT label.")
        assertThat((VolumeLabel.validate("MÚSICA")!!).message(en))
            .isEqualTo("\"Ú\" is not allowed: use English letters, digits, spaces and simple symbols.")
    }

    @Test
    fun `every usb failure has its own explanation`() {
        val failures = listOf(
            UsbStorageException.PermissionDenied("d"),
            UsbStorageException.DeviceGone("d"),
            UsbStorageException.DeviceClosed("d"),
            UsbStorageException.UnsupportedDevice("SCSI peripheral device type 0x05 is not a block device"),
            UsbStorageException.NoMedium(),
            UsbStorageException.WriteProtected(),
            UsbStorageException.TransportFailure("phase error"),
            UsbStorageException.CommandFailed(0x2A, 0x3, 0x0C, 0x00),
            IOException("boom"),
        )

        val messages = failures.map { describeUsbFailure(it, en) }

        assertThat(messages.toSet()).hasSize(failures.size)
        assertThat(describeUsbFailure(UsbStorageException.DeviceGone("d"), en)).isEqualTo("The USB stick was disconnected.")
        assertThat(describeUsbFailure(UsbStorageException.NoMedium(), en)).contains("No medium")
        assertThat(describeUsbFailure(failures[3], en)).contains("peripheral device type 0x05")
        assertThat(describeUsbFailure(failures[7], en)).contains("sense key 0x3")
    }

    @Test
    fun `notices say why the flow went back to the list`() {
        assertThat(UsbNotice.Disconnected.message(en)).isEqualTo("The USB stick was disconnected.")
        assertThat(UsbNotice.DisconnectedWhileFormatting.message(en)).contains("disconnected while formatting")
        assertThat(UsbNotice.Failure(UsbStorageException.NoMedium()).message(en)).contains("No medium")
        assertThat(UsbNotice.Disconnected.message(XmlStrings.of("-eu"))).isEqualTo("USB memoria deskonektatu da.")
    }

    @Test
    fun `geometry failures say that nothing was written`() {
        val failures = listOf(
            StickFormatResult.TooSmall(16_000_000),
            StickFormatResult.TooLarge(4_000_787_030_016, 2_199_023_255_552),
            StickFormatResult.UnsupportedBlockSize(2048),
        )

        failures.forEach { assertThat(failed(it).message).contains("Nothing was written") }
        assertThat(failed(failures[0]).title).isEqualTo("Stick too small")
        assertThat(failed(failures[1]).message).contains("2.2 TB")
        assertThat(failed(failures[2]).message).contains("2048-byte")
    }

    @Test
    fun `write and verification failures name where the stick failed`() {
        val write = failed(StickFormatResult.IoError(4096, IOException("write failed")))
        val flush = failed(StickFormatResult.IoError(null, IOException("flush failed")))
        val mismatch = failed(StickFormatResult.VerificationMismatch(FormatStructure.BOOT_SECTOR, 2048))

        assertThat(write.message).contains("block 4096")
        assertThat(write.message).contains("formatted again")
        assertThat(flush.message).contains("cache")
        assertThat(mismatch.title).isEqualTo("Verification failed")
        assertThat(mismatch.message).contains("boot sector at block 2048")
    }

    @Test
    fun `a cancelled format warns that the stick must be formatted again`() {
        val cancelled = failed(StickFormatResult.Cancelled)

        assertThat(cancelled.title).isEqualTo("Formatting cancelled")
        assertThat(cancelled.message).contains("not usable until it is formatted again")
    }

    @Test
    fun `a crashed formatter asks for another format`() {
        val crashed = FormatOutcome.Failed(null).text(en)

        assertThat(crashed.title).isEqualTo("Formatting failed")
        assertThat(crashed.message).contains("formatted again")
    }

    @Test
    fun `a success reports the label and volume size`() {
        val outcome = StickFormatResult.Success(VolumeLabel.of("sonora"), volumeBytes = 16_007_561_216, clusterBytes = 16_384)
            .toOutcome()

        assertThat(outcome).isEqualTo(FormatOutcome.Formatted("SONORA", 16_007_561_216))
        assertThat(StickFormatResult.Success(null, 64_000_000, 512).toOutcome())
            .isEqualTo(FormatOutcome.Formatted("NO NAME", 64_000_000))
    }

    @Test
    fun `every structure has a readable name`() {
        assertThat(FormatStructure.entries.map { it.displayName(en) }.toSet()).hasSize(FormatStructure.entries.size)
    }

    @Test
    fun `the stick is named with its capacity in the confirmation`() {
        val details = StickDetails("SanDisk Cruzer", "SanDisk", 16_008_609_792, 512, isWriteProtected = false)

        assertThat(details.summary(en)).isEqualTo("SanDisk Cruzer, 16.0 GB")
        assertThat(details.copy(name = null, usbName = null).summary(en)).isEqualTo("USB storage device, 16.0 GB")
    }

    @Test
    fun `the progress line says when a format is retrying before its first write`() {
        val device = DeviceItem("id", "Stick", "0781:5581", isSupported = true)
        val details = StickDetails("SanDisk", "SanDisk", 16_008_609_792, 512, false)
        val formatting = PrepareStep.Formatting(device, details, "SONORA")

        assertThat(formattingProgressText(formatting, en)).isEqualTo("Starting…")
        assertThat(formattingProgressText(formatting.copy(retrying = true), en)).isEqualTo("Retrying…")
        assertThat(
            formattingProgressText(
                formatting.copy(retrying = true, bytesWritten = 1_000_000, totalBytes = 4_000_000, fraction = 0.25f),
                en,
            ),
        ).isEqualTo("25% · 1.0 MB of 4.0 MB")
        assertThat(
            formattingProgressText(formatting.copy(bytesWritten = 1_500_000, totalBytes = 4_000_000, fraction = 0.375f), XmlStrings.of("-es")),
        ).isEqualTo("37 % · 1,5 MB de 4,0 MB")
    }

    private fun failed(result: StickFormatResult): FailureText = (result.toOutcome() as FormatOutcome.Failed).text(en)
}
