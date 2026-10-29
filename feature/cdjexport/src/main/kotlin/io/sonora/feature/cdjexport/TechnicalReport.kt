package io.sonora.feature.cdjexport

import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.feature.cdjexport.export.ExportResult
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** The app and the phone, as "Copy technical details" reports them. */
data class AppEnvironment(
    /** Version name and code: "0.1.0 (1)". */
    val appVersion: String,
    /** Android release: "16". */
    val androidVersion: String,
    val sdkInt: Int,
    /** Manufacturer and model: "realme RMX5011". */
    val deviceModel: String,
)

/** What went wrong in the Prepare USB flow. Stick fields are null when unknown (e.g. it never opened). */
internal data class FailureReport(
    /** The flow step that failed: "Opening the stick", "Formatting". */
    val step: String,
    /** USB vendor and product id: "0781:5581". */
    val usbId: String?,
    /** Manufacturer and product from the USB descriptors. */
    val usbName: String?,
    /** SCSI INQUIRY vendor, product and revision. */
    val identity: StorageIdentity?,
    val capacityBytes: Long?,
    val blockSize: Int?,
    /** The kind of failure: a format result ("IoError") or an exception type. */
    val failure: String,
    /** The block being accessed, when the failure is tied to one. */
    val lba: Long?,
    val cause: Throwable?,
    /** The failure of a first attempt that was retried once, when there was one. */
    val retriedAfter: Throwable? = null,
)

/**
 * A plain-text report of [failure] for a bug report or a support chat: app, Android, phone, stick, failure and the
 * whole exception chain with messages (no stack frames).
 */
internal fun technicalReport(environment: AppEnvironment, failure: FailureReport): String = buildString {
    appendLine("Sonora technical details")
    appendLine("App version: ${environment.appVersion}")
    appendLine("Android: ${environment.androidVersion} (SDK ${environment.sdkInt})")
    appendLine("Phone: ${environment.deviceModel}")
    appendLine("Step: ${failure.step}")
    val usb = when {
        failure.usbId == null -> UNKNOWN
        failure.usbName.isNullOrBlank() -> failure.usbId
        else -> "${failure.usbId} (${failure.usbName})"
    }
    appendLine("USB device: $usb")
    val inquiry = failure.identity?.let { "vendor \"${it.vendor}\", product \"${it.product}\", revision \"${it.revision}\"" }
    appendLine("INQUIRY: ${inquiry ?: UNKNOWN}")
    appendLine("Capacity: ${capacity(failure.capacityBytes, failure.blockSize)}")
    appendLine("Failure: ${failure.failure}")
    appendLine("LBA: ${failure.lba ?: "n/a"}")
    if (failure.cause == null) {
        append("Exception chain: none")
    } else {
        append("Exception chain:")
        appendChain(failure.cause)
    }
    failure.retriedAfter?.let {
        append("\nRetried once after re-opening the stick; the first attempt failed with:")
        appendChain(it)
    }
}

/**
 * A plain-text report of an export that did not fully succeed: app, Android, phone, stick, what failed and each
 * failure's exception chain; `null` when there is nothing to report.
 */
internal fun exportTechnicalReport(environment: AppEnvironment, stickName: String, result: ExportResult): String? {
    val failures: List<Pair<String, Throwable>> = when (result) {
        is ExportResult.Completed -> result.failed.map { "Track ${it.name} (${it.reason})" to it.cause }
        is ExportResult.Failed -> listOf("${result.step}; library changed: ${result.libraryChanged}" to result.cause)
        else -> emptyList()
    }
    if (failures.isEmpty()) return null
    return buildString {
        appendLine("Sonora technical details")
        appendLine("App version: ${environment.appVersion}")
        appendLine("Android: ${environment.androidVersion} (SDK ${environment.sdkInt})")
        appendLine("Phone: ${environment.deviceModel}")
        appendLine("Step: Exporting to Rekordbox")
        appendLine("Stick: $stickName")
        append("Result: ${result::class.simpleName}")
        failures.forEach { (what, cause) ->
            append("\nFailure: ").append(what)
            appendChain(cause)
        }
    }
}

private fun capacity(bytes: Long?, blockSize: Int?): String = when {
    bytes == null -> UNKNOWN
    blockSize == null || blockSize <= 0 -> String.format(Locale.US, "%,d bytes", bytes)
    else -> String.format(Locale.US, "%,d bytes (%,d blocks of %d bytes)", bytes, bytes / blockSize, blockSize)
}

/** One line per throwable from [first] down its causes (each listed once), with its suppressed failures below it. */
private fun StringBuilder.appendChain(first: Throwable) {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = first
    var prefix = ""
    while (current != null && seen.add(current)) {
        append("\n  ").append(prefix).append(describe(current))
        current.suppressed.forEach { append("\n    suppressed ").append(describe(it)) }
        prefix = "caused by "
        current = current.cause
    }
}

private fun describe(throwable: Throwable): String = "${throwable.javaClass.name}: ${throwable.message ?: "(no message)"}"

private const val UNKNOWN = "unknown"
