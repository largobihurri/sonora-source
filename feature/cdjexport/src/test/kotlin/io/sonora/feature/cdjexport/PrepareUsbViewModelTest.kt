package io.sonora.feature.cdjexport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.BlockDevice
import io.sonora.core.domain.storage.FormatStructure
import io.sonora.core.domain.storage.StickFormatProgress
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbBlockDevice
import io.sonora.core.domain.storage.UsbPermissionResult
import io.sonora.core.domain.storage.UsbStickFormatter
import io.sonora.core.domain.storage.UsbStorageDevice
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.domain.storage.UsbStorageGateway
import io.sonora.core.domain.storage.VolumeLabel
import io.sonora.core.domain.storage.VolumeLabelError
import io.sonora.feature.cdjexport.PrepareStep.ChooseDevice
import io.sonora.feature.cdjexport.PrepareStep.Finished
import io.sonora.feature.cdjexport.PrepareStep.Formatting
import io.sonora.feature.cdjexport.PrepareStep.Opening
import io.sonora.feature.cdjexport.PrepareStep.PermissionDenied
import io.sonora.feature.cdjexport.PrepareStep.Ready
import io.sonora.feature.cdjexport.PrepareStep.RequestingPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class PrepareUsbViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val sandisk = UsbStorageDevice(ID, 0x0781, 0x5567, "SanDisk", "Cruzer Blade", isSupported = true)
    private val uas = UsbStorageDevice("/dev/bus/usb/001/003", 0x174c, 0x55aa, "ASMedia", "SSD", isSupported = false)
    private val gateway = FakeGateway()
    private val formatter = FakeFormatter()
    private val stick = FakeStick()
    private lateinit var viewModel: PrepareUsbViewModel

    private val step get() = viewModel.state.value.step

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway.attached.value = listOf(sandisk)
        gateway.open = { stick }
        viewModel = PrepareUsbViewModel(gateway, formatter, dispatcher, ENVIRONMENT)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun openStick() {
        viewModel.selectDevice(ID)
        check(step is Ready) { "Expected the stick to open, got $step" }
    }

    /** Unplugs the stick (abandoning whatever step was running), plugs it in again and dismisses the notice. */
    private fun replug() {
        gateway.attached.value = emptyList()
        check(step == ChooseDevice) { "Expected the unplug to return to the list, got $step" }
        gateway.attached.value = listOf(sandisk)
        viewModel.dismissNotice()
    }

    /** Clears the ViewModel the way the framework does when the screen goes away for good. */
    private fun clearViewModel() {
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { viewModel } })[PrepareUsbViewModel::class]
        store.clear()
    }

    private fun startFormat() {
        openStick()
        viewModel.requestFormat()
        viewModel.onConfirmationChange(CONFIRMATION_WORD)
        viewModel.confirmFormat()
    }

    @Test
    fun `attached sticks are listed and unsupported ones explain why`() {
        gateway.attached.value = listOf(sandisk, uas)

        val devices = viewModel.state.value.devices

        assertThat(devices.map { it.name }).containsExactly("SanDisk Cruzer Blade", "ASMedia SSD").inOrder()
        assertThat(devices[0].isSelectable).isTrue()
        assertThat(devices[1].isSupported).isFalse()
        viewModel.selectDevice(uas.id)
        assertThat(gateway.permissionRequests).isEmpty()
        assertThat(step).isEqualTo(ChooseDevice)
    }

    @Test
    fun `a granted stick opens and shows its details with the default label`() {
        openStick()

        val ready = step as Ready
        assertThat(ready.details).isEqualTo(
            StickDetails(
                name = "SanDisk Cruzer Blade",
                usbName = "SanDisk Cruzer Blade",
                capacityBytes = 16_008_609_792,
                blockSize = 512,
                isWriteProtected = false,
            ),
        )
        assertThat(ready.label).isEqualTo("SONORA")
        assertThat(ready.labelError).isNull()
        assertThat(ready.canFormat).isTrue()
        assertThat(gateway.permissionRequests).containsExactly(ID)
    }

    @Test
    fun `a denied permission can be retried`() {
        val answers = ArrayDeque(listOf(UsbPermissionResult.DENIED, UsbPermissionResult.GRANTED))
        gateway.permission = { answers.removeFirst() }

        viewModel.selectDevice(ID)
        assertThat(step).isInstanceOf(PermissionDenied::class.java)
        viewModel.retryPermission()

        assertThat(step).isInstanceOf(Ready::class.java)
        assertThat(gateway.permissionRequests).containsExactly(ID, ID)
    }

    @Test
    fun `open failures return to the list with a specific message`() {
        val failures = listOf(
            UsbStorageException.NoMedium(),
            UsbStorageException.UnsupportedDevice("SCSI peripheral device type 0x05 is not a block device"),
            UsbStorageException.TransportFailure("Bulk-only reset recovery failed"),
            UsbStorageException.PermissionDenied(ID),
            UsbStorageException.CommandFailed(0x25, 0x2, 0x04, 0x00),
        )

        failures.forEach { failure ->
            gateway.open = { throw failure }
            viewModel.selectDevice(ID)

            assertThat(step).isEqualTo(ChooseDevice)
            assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.Failure(failure))
        }
        viewModel.dismissNotice()
        assertThat(viewModel.state.value.notice).isNull()
    }

    @Test
    fun `a write-protected stick cannot be formatted`() {
        gateway.open = { FakeStick(isWriteProtected = true) }
        openStick()

        viewModel.requestFormat()
        viewModel.onConfirmationChange(CONFIRMATION_WORD)
        viewModel.confirmFormat()

        val ready = step as Ready
        assertThat(ready.details.isWriteProtected).isTrue()
        assertThat(ready.canFormat).isFalse()
        assertThat(ready.formatBlockedReason).isEqualTo(FormatBlock.WRITE_PROTECTED)
        assertThat(ready.confirmation).isNull()
        assertThat(formatter.calls).isEqualTo(0)
    }

    @Test
    fun `the label is validated while typing`() {
        openStick()

        viewModel.onLabelChange("pioneer music")
        val tooLong = step as Ready
        viewModel.onLabelChange("A/B")
        val invalid = step as Ready
        viewModel.onLabelChange("my usb")
        val valid = step as Ready

        assertThat(tooLong.labelError).isEqualTo(VolumeLabelError.TooLong(13, 11))
        assertThat(tooLong.canFormat).isFalse()
        assertThat(invalid.labelError).isEqualTo(VolumeLabelError.InvalidCharacter('/'))
        assertThat(valid.label).isEqualTo("my usb")
        assertThat(valid.labelError).isNull()
        assertThat(valid.canFormat).isTrue()
    }

    @Test
    fun `an invalid label offers no confirmation`() {
        openStick()
        viewModel.onLabelChange("")

        viewModel.requestFormat()

        assertThat((step as Ready).confirmation).isNull()
    }

    @Test
    fun `formatting starts only after ERASE is typed exactly`() {
        openStick()
        viewModel.requestFormat()
        assertThat((step as Ready).confirmation).isEqualTo("")

        listOf("", "erase", "ERASE ", "ERAS", " ERASE").forEach { typed ->
            viewModel.onConfirmationChange(typed)
            assertThat((step as Ready).canConfirm).isFalse()
            viewModel.confirmFormat()
        }
        assertThat(formatter.calls).isEqualTo(0)

        viewModel.onConfirmationChange("ERASE")
        assertThat((step as Ready).canConfirm).isTrue()
        viewModel.confirmFormat()

        assertThat(formatter.calls).isEqualTo(1)
        assertThat(formatter.device).isSameInstanceAs(stick)
        assertThat(formatter.label).isEqualTo(VolumeLabel.of("SONORA"))
    }

    @Test
    fun `dismissing the confirmation formats nothing`() {
        openStick()
        viewModel.requestFormat()
        viewModel.onConfirmationChange("ERASE")

        viewModel.dismissConfirmation()
        viewModel.confirmFormat()

        assertThat((step as Ready).confirmation).isNull()
        assertThat(formatter.calls).isEqualTo(0)
        assertThat(stick.closeCount).isEqualTo(0)
    }

    @Test
    fun `progress is shown while formatting`() {
        val seen = mutableListOf<Formatting>()
        formatter.script = { onProgress, _ ->
            onProgress(StickFormatProgress(1L shl 20, 4L shl 20))
            seen += step as Formatting
            onProgress(StickFormatProgress(4L shl 20, 4L shl 20))
            seen += step as Formatting
            SUCCESS
        }

        startFormat()

        assertThat(seen.map { it.fraction }).containsExactly(0.25f, 1f).inOrder()
        assertThat(seen.map { it.bytesWritten }).containsExactly(1L shl 20, 4L shl 20).inOrder()
        assertThat(seen.first().totalBytes).isEqualTo(4L shl 20)
        assertThat(seen.first().label).isEqualTo("SONORA")
    }

    @Test
    fun `cancel stops the format, releases the stick and warns it must be formatted again`() {
        var cancelling: Formatting? = null
        formatter.script = { onProgress, isCancelled ->
            onProgress(StickFormatProgress(1L shl 20, 4L shl 20))
            check(!isCancelled())
            viewModel.cancelFormat()
            cancelling = step as Formatting
            if (isCancelled()) StickFormatResult.Cancelled else SUCCESS
        }

        startFormat()

        assertThat(cancelling!!.cancelling).isTrue()
        val outcome = (step as Finished).outcome as FormatOutcome.Failed
        assertThat(outcome).isEqualTo(StickFormatResult.Cancelled.toOutcome())
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a successful format releases the stick and shows the new volume`() {
        formatter.script = { _, _ ->
            assertThat(stick.closeCount).isEqualTo(0)
            SUCCESS
        }

        startFormat()

        assertThat((step as Finished).outcome).isEqualTo(FormatOutcome.Formatted("SONORA", 16_007_561_216))
        assertThat(stick.closeCount).isEqualTo(1)
        viewModel.back()
        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a failed format releases the stick and explains the failure`() {
        val mismatch = StickFormatResult.VerificationMismatch(FormatStructure.BOOT_SECTOR, 2048)
        formatter.script = { _, _ -> mismatch }

        startFormat()

        assertThat((step as Finished).outcome).isEqualTo(mismatch.toOutcome())
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `an unexpected formatter crash still releases the stick`() {
        formatter.script = { _, _ -> throw IllegalStateException("bug") }

        startFormat()

        assertThat((step as Finished).outcome).isInstanceOf(FormatOutcome.Failed::class.java)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `back during formatting is blocked with a hint`() {
        var afterBack: PrepareStep? = null
        formatter.script = { _, _ ->
            viewModel.back()
            afterBack = step
            SUCCESS
        }

        startFormat()

        assertThat((afterBack as Formatting).backBlocked).isTrue()
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `back from the details releases the stick`() {
        openStick()

        viewModel.back()

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `ejecting releases a stick opened for formatting, never one being formatted`() {
        openStick()

        viewModel.releaseStick()

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(stick.closeCount).isEqualTo(1)

        var duringFormat: PrepareStep? = null
        formatter.script = { _, _ ->
            viewModel.releaseStick()
            duringFormat = step
            SUCCESS
        }
        startFormat()

        assertThat(duringFormat).isInstanceOf(Formatting::class.java)
        assertThat(step).isInstanceOf(PrepareStep.Finished::class.java)
    }

    @Test
    fun `a stick unplugged before formatting returns to the list and is released`() {
        openStick()

        gateway.attached.value = emptyList()

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.Disconnected)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a stick unplugged while formatting returns to the list once the format stops`() {
        formatter.script = { _, _ ->
            gateway.attached.value = emptyList()
            assertThat(step).isInstanceOf(Formatting::class.java)
            StickFormatResult.IoError(lba = 10, cause = UsbStorageException.DeviceGone(ID))
        }

        startFormat()

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.DisconnectedWhileFormatting)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a stick that finishes opening after it was unplugged is released at once`() {
        val opening = CompletableDeferred<UsbBlockDevice>()
        gateway.open = { opening.await() }
        viewModel.selectDevice(ID)

        gateway.attached.value = emptyList()
        opening.complete(stick)

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a stick unplugged while asking permission returns to the list`() {
        val answer = CompletableDeferred<UsbPermissionResult>()
        gateway.permission = { answer.await() }
        viewModel.selectDevice(ID)

        gateway.attached.value = emptyList()
        answer.completeExceptionally(UsbStorageException.DeviceGone(ID))

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.Disconnected)
    }

    @Test
    fun `a late permission answer from an abandoned attempt does not drive the new one`() {
        val answers = ArrayDeque(List(2) { CompletableDeferred<UsbPermissionResult>() })
        val (abandoned, current) = answers.toList()
        gateway.permission = { answers.removeFirst().await() }
        viewModel.selectDevice(ID)
        replug()
        viewModel.selectDevice(ID)

        abandoned.complete(UsbPermissionResult.GRANTED)

        assertThat(step).isInstanceOf(RequestingPermission::class.java)
        assertThat(gateway.openRequests).isEqualTo(0)
        current.complete(UsbPermissionResult.GRANTED)
        assertThat(step).isInstanceOf(Ready::class.java)
        assertThat(gateway.openRequests).isEqualTo(1)
    }

    @Test
    fun `a late permission failure from an abandoned attempt leaves no spurious notice`() {
        val answers = ArrayDeque(List(2) { CompletableDeferred<UsbPermissionResult>() })
        val abandoned = answers.first()
        gateway.permission = { answers.removeFirst().await() }
        viewModel.selectDevice(ID)
        replug()
        viewModel.selectDevice(ID)

        abandoned.completeExceptionally(UsbStorageException.DeviceGone(ID))

        assertThat(step).isInstanceOf(RequestingPermission::class.java)
        assertThat(viewModel.state.value.notice).isNull()
    }

    @Test
    fun `a stick opened by an abandoned attempt is released and never shown`() {
        val openings = ArrayDeque(List(2) { CompletableDeferred<UsbBlockDevice>() })
        val (abandoned, current) = openings.toList()
        gateway.open = { openings.removeFirst().await() }
        val staleStick = FakeStick()
        viewModel.selectDevice(ID)
        replug()
        viewModel.selectDevice(ID)

        abandoned.complete(staleStick)

        assertThat(step).isInstanceOf(Opening::class.java)
        assertThat(staleStick.closeCount).isEqualTo(1)
        current.complete(stick)
        assertThat(step).isInstanceOf(Ready::class.java)
        viewModel.back()
        assertThat(stick.closeCount).isEqualTo(1)
        assertThat(staleStick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a late open failure from an abandoned attempt leaves no spurious notice`() {
        val openings = ArrayDeque(List(2) { CompletableDeferred<UsbBlockDevice>() })
        val abandoned = openings.first()
        gateway.open = { openings.removeFirst().await() }
        viewModel.selectDevice(ID)
        replug()
        viewModel.selectDevice(ID)

        abandoned.completeExceptionally(UsbStorageException.TransportFailure("Bulk-only reset recovery failed"))

        assertThat(step).isInstanceOf(Opening::class.java)
        assertThat(viewModel.state.value.notice).isNull()
    }

    @Test
    fun `an unexpected permission failure returns to the list with an error`() {
        val failure = IllegalStateException("UsbManager is not available")
        gateway.permission = { throw failure }

        viewModel.selectDevice(ID)

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.Failure(failure))
    }

    @Test
    fun `a stick that finishes opening after the screen went away is released`() {
        val opening = CompletableDeferred<UsbBlockDevice>()
        gateway.open = { opening.await() }
        viewModel.selectDevice(ID)
        check(step is Opening)

        clearViewModel()
        opening.complete(stick)

        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `clearing the screen while formatting cancels the format and releases the stick once`() {
        var cancelledAfterClear = false
        formatter.script = { _, isCancelled ->
            check(!isCancelled())
            clearViewModel()
            cancelledAfterClear = isCancelled()
            StickFormatResult.Cancelled
        }

        startFormat()

        assertThat(cancelledAfterClear).isTrue()
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `clearing the screen with a stick open releases it once`() {
        openStick()

        clearViewModel()

        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `open failures offer technical details`() {
        gateway.open = { throw UsbStorageException.TransportFailure("Could not claim the mass-storage interface") }

        viewModel.selectDevice(ID)

        val details = viewModel.state.value.noticeDetails!!
        assertThat(details).contains("Phone: realme RMX5011")
        assertThat(details).contains("Step: Opening the stick")
        assertThat(details).contains("USB device: 0781:5567 (SanDisk Cruzer Blade)")
        assertThat(details).contains("Failure: TransportFailure")
        assertThat(details).contains("Could not claim the mass-storage interface")
        viewModel.dismissNotice()
        assertThat(viewModel.state.value.noticeDetails).isNull()
    }

    @Test
    fun `a plain unplug notice has no technical details`() {
        openStick()

        gateway.attached.value = emptyList()

        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.Disconnected)
        assertThat(viewModel.state.value.noticeDetails).isNull()
    }

    @Test
    fun `a failed format offers technical details about the stick and the failure`() {
        formatter.script = { _, _ -> StickFormatResult.VerificationMismatch(FormatStructure.BOOT_SECTOR, 2048) }

        startFormat()

        val details = (step as Finished).technicalDetails!!
        assertThat(details).contains("Step: Formatting")
        assertThat(details).contains("INQUIRY: vendor \"SanDisk\", product \"Cruzer Blade\", revision \"1.00\"")
        assertThat(details).contains("Capacity: 16,008,609,792 bytes (31,266,816 blocks of 512 bytes)")
        assertThat(details).contains("Failure: VerificationMismatch")
        assertThat(details).contains("LBA: 2048")
    }

    @Test
    fun `success and cancel carry no technical details`() {
        startFormat()
        assertThat((step as Finished).technicalDetails).isNull()

        viewModel.back()
        formatter.script = { _, _ -> StickFormatResult.Cancelled }
        startFormat()
        assertThat((step as Finished).technicalDetails).isNull()
    }

    @Test
    fun `a transport failure on the very first write re-opens the stick after a pause and retries once`() {
        val reopened = FakeStick()
        val sticks = ArrayDeque(listOf(stick, reopened))
        gateway.open = { sticks.removeFirst() }
        val results = ArrayDeque(listOf(firstWriteFailure("CBW failed"), SUCCESS))
        formatter.script = { _, _ -> results.removeFirst() }

        startFormat()

        val waiting = step as Formatting
        assertThat(waiting.retrying).isTrue()
        assertThat(waiting.cancelling).isFalse()
        assertThat(stick.closeCount).isEqualTo(1)
        dispatcher.scheduler.advanceTimeBy(1_400)
        assertThat(gateway.openRequests).isEqualTo(1)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat((step as Finished).outcome).isEqualTo(FormatOutcome.Formatted("SONORA", 16_007_561_216))
        assertThat(gateway.openRequests).isEqualTo(2)
        assertThat(formatter.calls).isEqualTo(2)
        assertThat(formatter.device).isSameInstanceAs(reopened)
        assertThat(reopened.closeCount).isEqualTo(1)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    @Test
    fun `a progress report with nothing written yet still allows the first-write retry`() {
        val reopened = FakeStick()
        val sticks = ArrayDeque(listOf(stick, reopened))
        gateway.open = { sticks.removeFirst() }
        val results = ArrayDeque(listOf(firstWriteFailure("CBW failed"), SUCCESS))
        formatter.script = { onProgress, _ ->
            onProgress(StickFormatProgress(0, 4L shl 20))
            results.removeFirst()
        }

        startFormat()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat((step as Finished).outcome).isEqualTo(FormatOutcome.Formatted("SONORA", 16_007_561_216))
        assertThat(formatter.calls).isEqualTo(2)
    }

    @Test
    fun `a transport failure after blocks were written is not retried`() {
        val failure = StickFormatResult.IoError(lba = 2048, cause = UsbStorageException.TransportFailure("late"))
        formatter.script = { onProgress, _ ->
            onProgress(StickFormatProgress(1L shl 20, 4L shl 20))
            failure
        }

        startFormat()
        dispatcher.scheduler.advanceUntilIdle()

        val finished = step as Finished
        assertThat(finished.outcome).isEqualTo(failure.toOutcome())
        assertThat(finished.technicalDetails).contains("LBA: 2048")
        assertThat(formatter.calls).isEqualTo(1)
        assertThat(gateway.openRequests).isEqualTo(1)
    }

    @Test
    fun `other first-write failures are not retried`() {
        val rejected = StickFormatResult.IoError(lba = 0, cause = UsbStorageException.CommandFailed(0x2A, 0x3, 0x0C, 0))
        formatter.script = { _, _ -> rejected }

        startFormat()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat((step as Finished).outcome).isEqualTo(rejected.toOutcome())
        assertThat(formatter.calls).isEqualTo(1)
        assertThat(gateway.openRequests).isEqualTo(1)
    }

    @Test
    fun `a retry that fails again shows the error with details of both attempts`() {
        val sticks = ArrayDeque(listOf(stick, FakeStick()))
        gateway.open = { sticks.removeFirst() }
        val second = firstWriteFailure("second attempt failed")
        val results = ArrayDeque(listOf(firstWriteFailure("first attempt failed"), second))
        formatter.script = { _, _ -> results.removeFirst() }

        startFormat()
        dispatcher.scheduler.advanceUntilIdle()

        val finished = step as Finished
        assertThat(finished.outcome).isEqualTo(second.toOutcome())
        assertThat(finished.technicalDetails).contains("second attempt failed")
        assertThat(finished.technicalDetails).contains("Retried once after re-opening the stick")
        assertThat(finished.technicalDetails).contains("first attempt failed")
        assertThat(formatter.calls).isEqualTo(2)
        assertThat(gateway.openRequests).isEqualTo(2)
    }

    @Test
    fun `a stick that cannot be re-opened for the retry shows the first error with details`() {
        val first = firstWriteFailure("first attempt failed")
        val opens = ArrayDeque<suspend () -> UsbBlockDevice>(
            listOf({ stick }, { throw UsbStorageException.TransportFailure("re-open failed") }),
        )
        gateway.open = { opens.removeFirst()() }
        formatter.script = { _, _ -> first }

        startFormat()
        dispatcher.scheduler.advanceUntilIdle()

        val finished = step as Finished
        assertThat(finished.outcome).isEqualTo(first.toOutcome())
        assertThat(finished.technicalDetails).contains("re-open failed")
        assertThat(finished.technicalDetails).contains("first attempt failed")
        assertThat(formatter.calls).isEqualTo(1)
    }

    @Test
    fun `cancelling while waiting to retry stops without re-opening`() {
        formatter.script = { _, _ -> firstWriteFailure("CBW failed") }

        startFormat()
        viewModel.cancelFormat()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat((step as Finished).outcome).isEqualTo(StickFormatResult.Cancelled.toOutcome())
        assertThat(gateway.openRequests).isEqualTo(1)
        assertThat(formatter.calls).isEqualTo(1)
    }

    @Test
    fun `a stick unplugged while waiting to retry returns to the list`() {
        formatter.script = { _, _ -> firstWriteFailure("CBW failed") }

        startFormat()
        gateway.attached.value = emptyList()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(step).isEqualTo(ChooseDevice)
        assertThat(viewModel.state.value.notice).isEqualTo(UsbNotice.DisconnectedWhileFormatting)
        assertThat(viewModel.state.value.noticeDetails).contains("CBW failed")
        assertThat(gateway.openRequests).isEqualTo(1)
    }

    @Test
    fun `clearing the screen while waiting to retry re-opens nothing`() {
        formatter.script = { _, _ -> firstWriteFailure("CBW failed") }

        startFormat()
        clearViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(gateway.openRequests).isEqualTo(1)
        assertThat(stick.closeCount).isEqualTo(1)
    }

    private fun firstWriteFailure(message: String) =
        StickFormatResult.IoError(lba = 0, cause = UsbStorageException.TransportFailure(message))

    private class FakeGateway : UsbStorageGateway {
        val attached = MutableStateFlow<List<UsbStorageDevice>>(emptyList())
        override val devices = attached
        var permission: suspend () -> UsbPermissionResult = { UsbPermissionResult.GRANTED }
        var open: suspend () -> UsbBlockDevice = { error("no stick") }
        val permissionRequests = mutableListOf<String>()
        var openRequests = 0

        override suspend fun requestPermission(deviceId: String): UsbPermissionResult {
            permissionRequests += deviceId
            return permission()
        }

        override suspend fun open(deviceId: String): UsbBlockDevice {
            openRequests++
            return open()
        }
    }

    /** A 16 GB stick that stores nothing. */
    private class FakeStick(override val isWriteProtected: Boolean = false) : UsbBlockDevice {
        var closeCount = 0
        override val identity = StorageIdentity("SanDisk", "Cruzer Blade", "1.00")
        override val blockSize = 512
        override val blockCount = 31_266_816L
        override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = Unit
        override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = Unit
        override fun flush() = Unit
        override fun close() {
            closeCount++
        }
    }

    private class FakeFormatter : UsbStickFormatter {
        var calls = 0
        var device: BlockDevice? = null
        var label: VolumeLabel? = null
        var script: (onProgress: (StickFormatProgress) -> Unit, isCancelled: () -> Boolean) -> StickFormatResult =
            { _, _ -> SUCCESS }

        override fun format(
            device: BlockDevice,
            label: VolumeLabel?,
            onProgress: (StickFormatProgress) -> Unit,
            isCancelled: () -> Boolean,
        ): StickFormatResult {
            calls++
            this.device = device
            this.label = label
            return script(onProgress, isCancelled)
        }
    }

    private companion object {
        const val ID = "/dev/bus/usb/001/002"
        val ENVIRONMENT = AppEnvironment("0.1.0 (1)", "16", 36, "realme RMX5011")
        val SUCCESS = StickFormatResult.Success(VolumeLabel.of("SONORA"), volumeBytes = 16_007_561_216, clusterBytes = 16_384)
    }
}
