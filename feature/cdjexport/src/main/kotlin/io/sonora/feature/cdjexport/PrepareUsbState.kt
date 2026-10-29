package io.sonora.feature.cdjexport

import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.VolumeLabelError

/**
 * Everything the "Prepare USB stick" section shows: the attached sticks and where the user is in the flow. It holds no
 * text: the section words it in the app's language (see PrepareUsbText).
 */
data class PrepareUsbState(
    val devices: List<DeviceItem> = emptyList(),
    val step: PrepareStep = PrepareStep.ChooseDevice,
    /** Why the flow went back to the device list (e.g. the stick was unplugged), until the user dismisses it. */
    val notice: UsbNotice? = null,
    /** The technical report behind [notice] when a failure caused it, for "Copy technical details". */
    val noticeDetails: String? = null,
)

/** Why the flow went back to the device list. */
sealed interface UsbNotice {
    data object Disconnected : UsbNotice

    data object DisconnectedWhileFormatting : UsbNotice

    /** The stick could not be asked for or opened: [cause] says why. */
    data class Failure(val cause: Throwable) : UsbNotice
}

/**
 * An attached USB mass-storage device; one that is not [isSupported] is listed but cannot be selected. [name] comes from
 * its USB descriptors (`null` when they name nothing); [usbId] is the USB vendor and product id ("0781:5581").
 */
data class DeviceItem(
    val id: String,
    val name: String?,
    val usbId: String,
    val isSupported: Boolean,
) {
    val isSelectable: Boolean get() = isSupported
}

/** What an opened stick reports about itself. */
data class StickDetails(
    /** Vendor and product from SCSI INQUIRY, else [usbName]; `null` when neither names anything. */
    val name: String?,
    /** Manufacturer and product from the USB descriptors. */
    val usbName: String?,
    val capacityBytes: Long,
    val blockSize: Int,
    val isWriteProtected: Boolean,
)

/** Why formatting is not offered. */
enum class FormatBlock {
    WRITE_PROTECTED,
    INVALID_LABEL,
}

sealed interface PrepareStep {

    data object ChooseDevice : PrepareStep

    /** The system permission dialog is up for [device]. */
    data class RequestingPermission(val device: DeviceItem) : PrepareStep

    data class PermissionDenied(val device: DeviceItem) : PrepareStep

    /** Claiming [device] and reading its identity and capacity. */
    data class Opening(val device: DeviceItem) : PrepareStep

    /**
     * [device] is open (Android has unmounted it). [confirmation] is the text typed in the erase confirmation, or null
     * while the confirmation is not shown.
     */
    data class Ready(
        val device: DeviceItem,
        val details: StickDetails,
        val label: String,
        val labelError: VolumeLabelError?,
        val confirmation: String? = null,
    ) : PrepareStep {
        /** Why formatting is not offered, or null when it is. */
        val formatBlockedReason: FormatBlock?
            get() = when {
                details.isWriteProtected -> FormatBlock.WRITE_PROTECTED
                labelError != null -> FormatBlock.INVALID_LABEL
                else -> null
            }

        val canFormat: Boolean get() = formatBlockedReason == null

        /** The destructive action is enabled only once the user typed [CONFIRMATION_WORD] exactly. */
        val canConfirm: Boolean get() = canFormat && confirmation == CONFIRMATION_WORD
    }

    data class Formatting(
        val device: DeviceItem,
        val details: StickDetails,
        val label: String,
        val bytesWritten: Long = 0,
        val totalBytes: Long = 0,
        val fraction: Float = 0f,
        val cancelling: Boolean = false,
        /** The user pressed Back: explain that formatting is stopped with Cancel instead. */
        val backBlocked: Boolean = false,
        /** The first write failed before anything was written: the stick is re-opened and formatted once more. */
        val retrying: Boolean = false,
    ) : PrepareStep

    /** [technicalDetails] is the report behind a failed [outcome], for "Copy technical details". */
    data class Finished(val device: DeviceItem, val outcome: FormatOutcome, val technicalDetails: String? = null) :
        PrepareStep
}

sealed interface FormatOutcome {
    /** A FAT32 volume of [volumeBytes] labelled [label] ("NO NAME" without a label). */
    data class Formatted(val label: String, val volumeBytes: Long) : FormatOutcome

    /** The formatter returned [failure], or crashed when it is `null`. */
    data class Failed(val failure: StickFormatResult.Failure?) : FormatOutcome
}

/** The word the user types to confirm that the whole stick will be erased. */
const val CONFIRMATION_WORD = "ERASE"

/** The label offered by default; the user can change it before formatting. */
const val DEFAULT_LABEL = "SONORA"

const val FILE_SYSTEM_NAME = "FAT32 (MBR)"
