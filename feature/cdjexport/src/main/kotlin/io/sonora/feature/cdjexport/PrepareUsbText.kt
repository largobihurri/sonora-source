package io.sonora.feature.cdjexport

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.storage.FormatStructure
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.UsbBlockDevice
import io.sonora.core.domain.storage.UsbStorageDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.domain.storage.VolumeLabel
import io.sonora.core.domain.storage.VolumeLabelError
import java.text.NumberFormat
import java.util.Locale

// Copy of the "Prepare USB stick" section from string resources, kept pure so it is unit-tested in every language.

private val UNITS = listOf("kB", "MB", "GB", "TB", "PB")

/** Decimal size with one decimal, the way stick makers print it: 16_008_609_792 bytes is "16.0 GB" ("16,0 GB" in Spanish). */
fun formatCapacity(bytes: Long, locale: Locale): String {
    if (bytes < 1_000) return "${LocaleFormats.integer(bytes, locale)} B"
    var value = bytes / 1_000.0
    var unit = 0
    // 999.95 and up would round to "1000.0": show the next unit instead.
    while (value >= 999.95 && unit < UNITS.lastIndex) {
        value /= 1_000.0
        unit++
    }
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)
    return "$number ${UNITS[unit]}"
}

/** Exact size with digit grouping: "16,008,609,792 bytes". */
fun formatExactSize(bytes: Long, strings: Strings): String = strings.plural(
    io.sonora.core.designsystem.R.plurals.ds_file_size_bytes,
    bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    LocaleFormats.integer(bytes, strings.locale),
)

internal fun UsbStorageDevice.toItem(): DeviceItem {
    val maker = manufacturerName?.trim().orEmpty()
    val product = productName?.trim().orEmpty()
    val name = when {
        product.isEmpty() -> maker
        maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
        else -> "$maker $product"
    }
    return DeviceItem(
        id = id,
        name = name.ifEmpty { null },
        usbId = String.format(Locale.ROOT, "%04X:%04X", vendorId, productId),
        isSupported = isSupported,
    )
}

/** The device's name, or a generic one when its descriptors name nothing. */
internal fun DeviceItem.displayName(strings: Strings): String = name ?: strings.get(R.string.cdj_device_unnamed)

/** The line under a listed device: its USB id, or why it cannot be selected. */
internal fun DeviceItem.detail(strings: Strings): String =
    if (isSupported) strings.get(R.string.cdj_device_detail, usbId) else strings.get(R.string.cdj_device_unsupported)

internal fun UsbBlockDevice.toDetails(usbName: String?): StickDetails = StickDetails(
    name = "${identity.vendor} ${identity.product}".trim().ifEmpty { usbName },
    usbName = usbName,
    capacityBytes = capacityBytes,
    blockSize = blockSize,
    isWriteProtected = isWriteProtected,
)

/** How the confirmation names the stick: "SanDisk Cruzer Blade, 16.0 GB". */
internal fun StickDetails.summary(strings: Strings): String =
    strings.get(R.string.cdj_stick_summary, displayName(strings), formatCapacity(capacityBytes, strings.locale))

internal fun StickDetails.displayName(strings: Strings): String = name ?: strings.get(R.string.cdj_device_unnamed)

/** "16.0 GB (16,008,609,792 bytes)". */
internal fun StickDetails.capacityText(strings: Strings): String = strings.get(
    R.string.cdj_capacity_value,
    formatCapacity(capacityBytes, strings.locale),
    formatExactSize(capacityBytes, strings),
)

internal fun VolumeLabelError.message(strings: Strings): String = when (this) {
    VolumeLabelError.Empty -> strings.get(R.string.cdj_label_empty, VolumeLabel.MAX_LENGTH)
    is VolumeLabelError.TooLong -> strings.get(R.string.cdj_label_too_long, length, maxLength)
    is VolumeLabelError.InvalidCharacter -> if (character in ' '..'~') {
        strings.get(R.string.cdj_label_invalid_character, character.toString())
    } else {
        strings.get(R.string.cdj_label_non_ascii, character.toString())
    }
}

internal fun FormatBlock.message(strings: Strings): String = when (this) {
    FormatBlock.WRITE_PROTECTED -> strings.get(R.string.cdj_write_protected)
    FormatBlock.INVALID_LABEL -> strings.get(R.string.cdj_format_blocked_label)
}

/** Why a stick could not be used, for any failure the gateway or a device throws. */
internal fun describeUsbFailure(failure: Throwable, strings: Strings): String = when (failure) {
    is UsbStorageException.PermissionDenied -> strings.get(R.string.cdj_usb_permission_denied)
    is UsbStorageException.DeviceGone -> strings.get(R.string.cdj_usb_disconnected)
    is UsbStorageException.DeviceClosed -> strings.get(R.string.cdj_usb_closed)
    is UsbStorageException.UnsupportedDevice -> strings.get(R.string.cdj_usb_unsupported, failure.reason)
    is UsbStorageException.NoMedium -> strings.get(R.string.cdj_usb_no_medium)
    is UsbStorageException.WriteProtected -> strings.get(R.string.cdj_usb_write_protected)
    is UsbStorageException.TransportFailure -> strings.get(R.string.cdj_usb_transport)
    is UsbStorageException.CommandFailed ->
        strings.get(R.string.cdj_usb_command_failed, failure.senseKey, failure.asc, failure.ascq)
    else -> strings.get(R.string.cdj_usb_other, failure.message ?: failure.javaClass.simpleName)
}

internal fun UsbNotice.message(strings: Strings): String = when (this) {
    UsbNotice.Disconnected -> strings.get(R.string.cdj_usb_disconnected)
    UsbNotice.DisconnectedWhileFormatting -> strings.get(R.string.cdj_usb_disconnected_while_formatting)
    is UsbNotice.Failure -> describeUsbFailure(cause, strings)
}

internal fun StickFormatResult.toOutcome(): FormatOutcome = when (this) {
    is StickFormatResult.Success -> FormatOutcome.Formatted(label?.text ?: NO_LABEL, volumeBytes)
    is StickFormatResult.Failure -> FormatOutcome.Failed(this)
}

/** The title and message of a failed format. */
internal data class FailureText(val title: String, val message: String)

internal fun FormatOutcome.Failed.text(strings: Strings): FailureText {
    val locale = strings.locale
    fun text(title: Int, message: String) = FailureText(strings.get(title), message)
    return when (val failure = failure) {
        null -> text(R.string.cdj_format_failed_title, strings.get(R.string.cdj_format_failed_message))
        is StickFormatResult.TooSmall -> text(
            R.string.cdj_too_small_title,
            strings.get(R.string.cdj_too_small_message, formatCapacity(failure.deviceBytes, locale)),
        )
        is StickFormatResult.TooLarge -> text(
            R.string.cdj_too_large_title,
            strings.get(
                R.string.cdj_too_large_message,
                formatCapacity(failure.deviceBytes, locale),
                formatCapacity(failure.maximumBytes, locale),
            ),
        )
        is StickFormatResult.UnsupportedBlockSize ->
            text(R.string.cdj_block_size_title, strings.get(R.string.cdj_block_size_message, failure.blockSize))
        is StickFormatResult.IoError -> when {
            failure.cause is UsbStorageException.WriteProtected ->
                text(R.string.cdj_write_protected_title, strings.get(R.string.cdj_write_protected_message))
            else -> {
                val cause = describeUsbFailure(failure.cause, strings)
                val message = failure.lba?.let { strings.get(R.string.cdj_write_error_at_block, it, cause) }
                    ?: strings.get(R.string.cdj_write_error_cache, cause)
                text(R.string.cdj_write_error_title, message)
            }
        }
        is StickFormatResult.VerificationMismatch -> text(
            R.string.cdj_verification_title,
            strings.get(R.string.cdj_verification_message, failure.structure.displayName(strings), failure.lba),
        )
        StickFormatResult.Cancelled ->
            text(R.string.cdj_format_cancelled_title, strings.get(R.string.cdj_format_cancelled_message))
    }
}

internal fun FormatStructure.displayName(strings: Strings): String = strings.get(
    when (this) {
        FormatStructure.PARTITION_TABLE -> R.string.cdj_structure_partition_table
        FormatStructure.BOOT_SECTOR -> R.string.cdj_structure_boot_sector
        FormatStructure.FS_INFO -> R.string.cdj_structure_fs_info
        FormatStructure.BACKUP_BOOT_SECTOR -> R.string.cdj_structure_backup_boot_sector
        FormatStructure.BACKUP_FS_INFO -> R.string.cdj_structure_backup_fs_info
        FormatStructure.FAT -> R.string.cdj_structure_fat
        FormatStructure.BACKUP_FAT -> R.string.cdj_structure_backup_fat
        FormatStructure.ROOT_DIRECTORY -> R.string.cdj_structure_root_directory
    },
)

/** The line under the progress bar: percentage and bytes, or what happens before the first write. */
internal fun formattingProgressText(step: PrepareStep.Formatting, strings: Strings): String = when {
    step.totalBytes > 0 -> strings.get(
        R.string.cdj_format_progress,
        (step.fraction * 100).toInt(),
        formatCapacity(step.bytesWritten, strings.locale),
        formatCapacity(step.totalBytes, strings.locale),
    )
    step.retrying -> strings.get(R.string.cdj_retrying)
    else -> strings.get(R.string.cdj_starting)
}

/** The volume label FAT reports for a volume without one. */
private const val NO_LABEL = "NO NAME"
