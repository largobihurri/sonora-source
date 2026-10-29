package io.sonora.feature.cdjexport

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbBlockDevice
import io.sonora.core.domain.storage.UsbPermissionResult
import io.sonora.core.domain.storage.UsbStickFormatter
import io.sonora.core.domain.storage.UsbStorageDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.domain.storage.UsbStorageGateway
import io.sonora.core.domain.storage.VolumeLabel
import io.sonora.feature.cdjexport.PrepareStep.ChooseDevice
import io.sonora.feature.cdjexport.PrepareStep.Finished
import io.sonora.feature.cdjexport.PrepareStep.Formatting
import io.sonora.feature.cdjexport.PrepareStep.Opening
import io.sonora.feature.cdjexport.PrepareStep.PermissionDenied
import io.sonora.feature.cdjexport.PrepareStep.Ready
import io.sonora.feature.cdjexport.PrepareStep.RequestingPermission
import io.sonora.feature.cdjexport.di.CdjExportIoDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Prepare USB stick": pick an attached stick, get permission, open it, choose a label, confirm by typing
 * [CONFIRMATION_WORD], then format it as FAT32 (MBR) for Pioneer players.
 *
 * Safety rules: only a stick the user selected and confirmed is written; an opened stick is always closed again (back,
 * unplug, success, failure, cancel or the screen going away) so Android can mount it; a stick that disappears while
 * any step but the format itself is running sends the user back to the list.
 *
 * When the very first write of a format fails with a transport failure before anything was written (seen right after
 * claiming a stick, apparently while Android is still unmounting it), the stick is closed, re-opened after
 * [RETRY_SETTLE_MS] and formatted once more; a failure after some blocks were written is never retried. Every failure
 * keeps a plain-text technical report for "Copy technical details".
 */
@HiltViewModel
class PrepareUsbViewModel @Inject constructor(
    private val gateway: UsbStorageGateway,
    private val formatter: UsbStickFormatter,
    @CdjExportIoDispatcher private val io: CoroutineDispatcher,
    private val environment: AppEnvironment,
) : ViewModel() {

    private val _state = MutableStateFlow(PrepareUsbState())
    val state: StateFlow<PrepareUsbState> = _state.asStateFlow()

    /** The stick held open while the user prepares the format; a running format owns its stick instead. Main thread. */
    private var openStick: UsbBlockDevice? = null

    /**
     * Identifies the current select attempt (its permission request and open). Moving on, by selecting again or
     * going back to the list, starts a new one, so a late answer or stick from an abandoned attempt never drives it.
     * Main thread.
     */
    private var currentAttempt = 0L

    /** Polled by the formatter (on the IO thread) before each write. */
    private val cancelRequested = AtomicBoolean(false)

    init {
        viewModelScope.launch { gateway.devices.collect(::onDevicesChanged) }
    }

    fun selectDevice(id: String) {
        val current = _state.value
        if (current.step != ChooseDevice) return
        val device = current.devices.firstOrNull { it.id == id && it.isSelectable } ?: return
        requestAccess(device)
    }

    fun retryPermission() {
        val denied = _state.value.step as? PermissionDenied ?: return
        requestAccess(denied.device)
    }

    /** Back leaves the details, a denied permission or a result; during a format it only shows a hint. */
    fun back() {
        when (_state.value.step) {
            is Formatting -> updateStep<Formatting> { it.copy(backBlocked = true) }
            is Ready, is PermissionDenied, is Finished -> returnToList(notice = null)
            ChooseDevice, is RequestingPermission, is Opening -> Unit
        }
    }

    /**
     * Gives the stick back to Android before it is ejected: a stick opened for formatting (or being asked for or opened)
     * is closed and the list shown again. A running format keeps its stick; a finished one has closed it already.
     */
    fun releaseStick() {
        when (_state.value.step) {
            is Ready, is PermissionDenied, is RequestingPermission, is Opening -> returnToList(notice = null)
            ChooseDevice, is Formatting, is Finished -> Unit
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, noticeDetails = null) }

    fun onLabelChange(text: String) =
        updateStep<Ready> { it.copy(label = text, labelError = VolumeLabel.validate(text)) }

    /** Shows the erase confirmation, unless formatting is blocked (write protection, invalid label). */
    fun requestFormat() = updateStep<Ready> { if (it.canFormat) it.copy(confirmation = "") else it }

    fun onConfirmationChange(text: String) =
        updateStep<Ready> { if (it.confirmation != null) it.copy(confirmation = text) else it }

    fun dismissConfirmation() = updateStep<Ready> { it.copy(confirmation = null) }

    /** Formats the open stick, only from a confirmation where [CONFIRMATION_WORD] was typed exactly. */
    fun confirmFormat() {
        val ready = _state.value.step as? Ready ?: return
        if (!ready.canConfirm) return
        val label = VolumeLabel.ofOrNull(ready.label) ?: return
        val stick = openStick ?: return
        openStick = null
        cancelRequested.set(false)
        _state.update {
            it.copy(step = Formatting(ready.device, ready.details, label.text), notice = null, noticeDetails = null)
        }
        viewModelScope.launch { format(ready.device, stick, label) }
    }

    fun cancelFormat() {
        if (_state.value.step !is Formatting) return
        cancelRequested.set(true)
        updateStep<Formatting> { it.copy(cancelling = true, backBlocked = false) }
    }

    override fun onCleared() {
        // A running format sees this before its next write, stops and closes its own stick.
        cancelRequested.set(true)
        openStick?.let(::closeQuietly)
        openStick = null
    }

    private fun requestAccess(device: DeviceItem) {
        val attempt = ++currentAttempt
        _state.update { it.copy(step = RequestingPermission(device), notice = null, noticeDetails = null) }
        viewModelScope.launch {
            val answer = try {
                gateway.requestPermission(device.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure !is UsbStorageException) Log.e(TAG, "Asking permission for ${device.id} failed", failure)
                if (isCurrent<RequestingPermission>(attempt)) {
                    returnToList(UsbNotice.Failure(failure), failureReport(device, "Asking for permission", failure))
                }
                return@launch
            }
            if (!isCurrent<RequestingPermission>(attempt)) return@launch
            if (answer == UsbPermissionResult.GRANTED) open(device, attempt) else setStep(PermissionDenied(device))
        }
    }

    private suspend fun open(device: DeviceItem, attempt: Long) {
        setStep(Opening(device))
        val screen = currentCoroutineContext().job
        // Not cancellable: a stick claimed while the screen goes away must still come back here to be released.
        withContext(NonCancellable) {
            val stick = try {
                gateway.open(device.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Cannot open ${device.id}", failure)
                if (screen.isActive && isCurrent<Opening>(attempt)) {
                    returnToList(UsbNotice.Failure(failure), failureReport(device, "Opening the stick", failure))
                }
                return@withContext
            }
            if (screen.isActive && isCurrent<Opening>(attempt)) {
                openStick = stick
                val labelError = VolumeLabel.validate(DEFAULT_LABEL)
                setStep(Ready(device, stick.toDetails(device.name), DEFAULT_LABEL, labelError))
            } else {
                // Unplugged, abandoned or the screen went away while opening: give the stick straight back to Android.
                withContext(io) { closeQuietly(stick) }
            }
        }
    }

    private suspend fun format(device: DeviceItem, stick: UsbBlockDevice, label: VolumeLabel) {
        val facts = StickFacts(stick.identity, stick.capacityBytes, stick.blockSize)
        val first = formatOnce(device, stick, label)
        if (!first.isRetryable || cancelRequested.get() || !isAttached(device)) {
            finish(device, facts, first, retriedAfter = null)
            return
        }
        val firstFailure = first.result as StickFormatResult.IoError
        val firstCause = firstFailure.cause
        Log.w(TAG, "The first write to ${device.id} failed before anything was written; retrying once", firstCause)
        updateStep<Formatting> { it.copy(retrying = true) }
        delay(RETRY_SETTLE_MS)
        if (cancelRequested.get()) return finish(device, facts, FormatRun(StickFormatResult.Cancelled), retriedAfter = null)
        if (!isAttached(device)) return finish(device, facts, first, retriedAfter = null)
        val reopened = try {
            reopen(device)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Re-opening ${device.id} to retry the format failed", failure)
            val details = formatReport(device, facts, "IoError, then re-opening failed", firstFailure.lba, failure, firstCause)
            if (failure is UsbStorageException.DeviceGone || !isAttached(device)) {
                returnToList(UsbNotice.DisconnectedWhileFormatting, details)
            } else {
                setStep(Finished(device, firstFailure.toOutcome(), details))
            }
            return
        }
        val retry = reopened?.let { formatOnce(device, it, label) } ?: FormatRun(StickFormatResult.Cancelled)
        finish(device, facts, retry, retriedAfter = firstCause)
    }

    /** Runs the formatter once on [stick] and always closes it. */
    private suspend fun formatOnce(device: DeviceItem, stick: UsbBlockDevice, label: VolumeLabel): FormatRun {
        var wroteAnything = false
        return try {
            val result = withContext(io) {
                formatter.format(
                    stick,
                    label,
                    onProgress = { progress ->
                        if (progress.bytesWritten > 0) wroteAnything = true
                        updateStep<Formatting> {
                            it.copy(
                                bytesWritten = progress.bytesWritten,
                                totalBytes = progress.totalBytes,
                                fraction = progress.fraction,
                            )
                        }
                    },
                    isCancelled = { cancelRequested.get() || !isActive },
                )
            }
            FormatRun(result, wroteAnything = wroteAnything)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Formatting ${device.id} failed unexpectedly", failure)
            FormatRun(result = null, crash = failure, wroteAnything = wroteAnything)
        } finally {
            withContext(NonCancellable + io) { closeQuietly(stick) }
        }
    }

    /** Opens [device] again for the retry; null when the format was cancelled or the screen went away meanwhile. */
    private suspend fun reopen(device: DeviceItem): UsbBlockDevice? {
        val screen = currentCoroutineContext().job
        // Not cancellable: a stick claimed while the screen goes away must still come back here to be released.
        return withContext(NonCancellable) {
            val stick = gateway.open(device.id)
            if (screen.isActive && !cancelRequested.get()) {
                stick
            } else {
                withContext(io) { closeQuietly(stick) }
                null
            }
        }
    }

    private fun finish(device: DeviceItem, facts: StickFacts, run: FormatRun, retriedAfter: Throwable?) {
        val result = run.result
        val failed = result == null || (result is StickFormatResult.Failure && result !is StickFormatResult.Cancelled)
        val details = if (failed) {
            val failure = if (result == null) "Unexpected ${run.crash?.javaClass?.name}" else result::class.simpleName
            val cause = (result as? StickFormatResult.IoError)?.cause ?: run.crash
            formatReport(device, facts, failure.orEmpty(), run.lba, cause, retriedAfter)
        } else {
            null
        }
        val unplugged = result is StickFormatResult.IoError &&
            (result.cause is UsbStorageException.DeviceGone || !isAttached(device))
        if (unplugged) {
            returnToList(UsbNotice.DisconnectedWhileFormatting, details)
        } else {
            setStep(Finished(device, result?.toOutcome() ?: FormatOutcome.Failed(null), details))
        }
    }

    private fun failureReport(device: DeviceItem, step: String, failure: Throwable): String =
        technicalReport(
            environment,
            FailureReport(step, device.usbId, device.name, null, null, null, failure.javaClass.simpleName, null, failure),
        )

    private fun formatReport(
        device: DeviceItem,
        facts: StickFacts,
        failure: String,
        lba: Long?,
        cause: Throwable?,
        retriedAfter: Throwable?,
    ): String = technicalReport(
        environment,
        FailureReport(
            step = "Formatting",
            usbId = device.usbId,
            usbName = device.name,
            identity = facts.identity,
            capacityBytes = facts.capacityBytes,
            blockSize = facts.blockSize,
            failure = failure,
            lba = lba,
            cause = cause,
            retriedAfter = retriedAfter,
        ),
    )

    private fun isAttached(device: DeviceItem): Boolean = _state.value.devices.any { it.id == device.id }

    private fun onDevicesChanged(attached: List<UsbStorageDevice>) {
        val items = attached.map { it.toItem() }
        _state.update { it.copy(devices = items) }
        val step = _state.value.step
        // A running format reports the unplug itself; a finished one has already released the stick.
        if (step is Formatting || step is Finished) return
        val selected = step.selectedDevice ?: return
        if (items.none { it.id == selected.id }) returnToList(UsbNotice.Disconnected)
    }

    private fun returnToList(notice: UsbNotice?, details: String? = null) {
        currentAttempt++
        openStick?.let { stick -> viewModelScope.launch(io) { closeQuietly(stick) } }
        openStick = null
        _state.update { it.copy(step = ChooseDevice, notice = notice, noticeDetails = details) }
    }

    private fun setStep(step: PrepareStep) = _state.update { it.copy(step = step) }

    private inline fun <reified T : PrepareStep> updateStep(crossinline transform: (T) -> PrepareStep) =
        _state.update { current -> (current.step as? T)?.let { current.copy(step = transform(it)) } ?: current }

    /** [attempt] is still the current one and has reached step [T]. */
    private inline fun <reified T : PrepareStep> isCurrent(attempt: Long): Boolean =
        attempt == currentAttempt && _state.value.step is T

    private fun closeQuietly(stick: UsbBlockDevice) {
        try {
            stick.close()
        } catch (failure: Exception) {
            Log.w(TAG, "Closing the USB stick failed", failure)
        }
    }

    private companion object {
        const val TAG = "SonoraPrepareUsb"
    }
}

/** How long a stick rests, closed, before the one retry of a format whose first write failed. */
internal const val RETRY_SETTLE_MS = 1_500L

/** What the technical report says about an opened stick, kept after it is closed. */
private class StickFacts(val identity: StorageIdentity, val capacityBytes: Long, val blockSize: Int)

/** One formatter run: its [result], or the exception it crashed with instead. */
private class FormatRun(
    val result: StickFormatResult?,
    val crash: Exception? = null,
    /** At least one write succeeded (the formatter reported progress). */
    val wroteAnything: Boolean = false,
) {
    val lba: Long?
        get() = when (result) {
            is StickFormatResult.IoError -> result.lba
            is StickFormatResult.VerificationMismatch -> result.lba
            else -> null
        }

    /** The very first write failed on the USB link: the stick may just not have settled after being claimed. */
    val isRetryable: Boolean
        get() = !wroteAnything && result is StickFormatResult.IoError &&
            result.cause is UsbStorageException.TransportFailure
}

private val PrepareStep.selectedDevice: DeviceItem?
    get() = when (this) {
        ChooseDevice -> null
        is RequestingPermission -> device
        is PermissionDenied -> device
        is Opening -> device
        is Ready -> device
        is Formatting -> device
        is Finished -> device
    }
