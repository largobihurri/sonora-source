package io.sonora.core.usbstorage.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.storage.UsbStorageException
import io.sonora.core.usbstorage.protocol.ScriptedBulkTransport.Companion.csw
import io.sonora.core.usbstorage.protocol.ScriptedBulkTransport.Event
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class BulkOnlyProtocolTest {

    private val transport = ScriptedBulkTransport()
    private val protocol = BulkOnlyProtocol(transport)
    private val inquiry = byteArrayOf(0x12, 0, 0, 0, 36, 0)

    private val resetRecovery = listOf(Event.Reset, Event.ClearHalt(BulkEndpoint.IN), Event.ClearHalt(BulkEndpoint.OUT))

    @Test
    fun `a data-in command block wrapper is 31 little-endian bytes with the direction flag set`() {
        transport.queueIn(ByteArray(36) { 1 }, csw(tag = 1))

        val outcome = protocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)

        val cbw = transport.outWrites.single()
        assertThat(cbw.size).isEqualTo(31)
        val fields = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(fields.getInt(0)).isEqualTo(0x43425355)
        assertThat(fields.getInt(4)).isEqualTo(1)
        assertThat(fields.getInt(8)).isEqualTo(36)
        assertThat(cbw[12]).isEqualTo(0x80.toByte())
        assertThat(cbw[13]).isEqualTo(0)
        assertThat(cbw[14]).isEqualTo(6)
        assertThat(cbw.copyOfRange(15, 21)).isEqualTo(inquiry)
        assertThat(cbw.copyOfRange(21, 31).all { it == 0.toByte() }).isTrue()
        assertThat(outcome).isEqualTo(CommandOutcome(CommandStatus.PASSED, transferred = 36, residue = 0))
    }

    @Test
    fun `data-out and no-data commands clear the direction flag and tags increment per command`() {
        val payload = ByteArray(512) { it.toByte() }
        transport.queueIn(csw(tag = 1), csw(tag = 2))

        protocol.execute(ByteArray(10), DataDirection.OUT, payload, 0, 512)
        protocol.execute(ByteArray(6), DataDirection.NONE)

        val (firstCbw, data, secondCbw) = transport.outWrites
        assertThat(firstCbw[12]).isEqualTo(0)
        assertThat(ByteBuffer.wrap(firstCbw).order(ByteOrder.LITTLE_ENDIAN).getInt(8)).isEqualTo(512)
        assertThat(data).isEqualTo(payload)
        assertThat(secondCbw[12]).isEqualTo(0)
        assertThat(ByteBuffer.wrap(secondCbw).order(ByteOrder.LITTLE_ENDIAN).getInt(4)).isEqualTo(2)
        assertThat(ByteBuffer.wrap(secondCbw).order(ByteOrder.LITTLE_ENDIAN).getInt(8)).isEqualTo(0)
    }

    @Test
    fun `the logical unit goes into the command block wrapper`() {
        transport.queueIn(csw(tag = 1))

        BulkOnlyProtocol(transport, lun = 2).execute(ByteArray(6), DataDirection.NONE)

        assertThat(transport.outWrites.single()[13]).isEqualTo(2)
    }

    @Test
    fun `partial bulk transfers are reassembled in both directions`() {
        val payload = ByteArray(2048) { (it * 7).toByte() }
        transport.queueOutResult(31, 100, 1000)
        transport.queueIn(csw(tag = 1))
        protocol.execute(ByteArray(10), DataDirection.OUT, payload, 0, 2048)

        val incoming = ByteArray(2048) { (it * 3).toByte() }
        transport.queueIn(incoming.copyOfRange(0, 512), incoming.copyOfRange(512, 1024), incoming.copyOfRange(1024, 2048))
        transport.queueIn(csw(tag = 2))
        val buffer = ByteArray(2048)
        val outcome = protocol.execute(ByteArray(10), DataDirection.IN, buffer, 0, 2048)

        assertThat(transport.outWrites.drop(1).take(3).fold(ByteArray(0)) { all, chunk -> all + chunk }).isEqualTo(payload)
        assertThat(buffer).isEqualTo(incoming)
        assertThat(outcome.transferred).isEqualTo(2048)
    }

    @Test
    fun `a short packet ends the data-in phase and the residue is reported`() {
        transport.queueIn(ByteArray(20) { 9 }, csw(tag = 1, residue = 16))

        val outcome = protocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)

        assertThat(outcome).isEqualTo(CommandOutcome(CommandStatus.PASSED, transferred = 20, residue = 16))
        assertThat(transport.controlAndInEvents).containsExactly(Event.In(36), Event.In(13)).inOrder()
    }

    @Test
    fun `a stall in the data phase clears the halt and then reads the status`() {
        transport.queueInFailure()
        transport.queueIn(csw(tag = 1, residue = 36, status = 1))

        val outcome = protocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)

        assertThat(outcome).isEqualTo(CommandOutcome(CommandStatus.FAILED, transferred = 0, residue = 36))
        assertThat(transport.controlAndInEvents)
            .containsExactly(Event.In(36), Event.ClearHalt(BulkEndpoint.IN), Event.In(13)).inOrder()
    }

    @Test
    fun `a stall on data-out clears the bulk out halt`() {
        transport.queueOutResult(31, -1)
        transport.queueIn(csw(tag = 1, residue = 512, status = 1))

        val outcome = protocol.execute(ByteArray(10), DataDirection.OUT, ByteArray(512), 0, 512)

        assertThat(outcome.status).isEqualTo(CommandStatus.FAILED)
        assertThat(transport.controlAndInEvents).containsExactly(Event.ClearHalt(BulkEndpoint.OUT), Event.In(13)).inOrder()
    }

    @Test
    fun `a stalled status read is retried once after clearing the halt`() {
        transport.queueInFailure()
        transport.queueIn(csw(tag = 1))

        val outcome = protocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(outcome.status).isEqualTo(CommandStatus.PASSED)
        assertThat(transport.controlAndInEvents)
            .containsExactly(Event.In(13), Event.ClearHalt(BulkEndpoint.IN), Event.In(13)).inOrder()
    }

    @Test
    fun `a status with a bad signature triggers reset recovery and the command is retried`() {
        transport.queueIn(csw(tag = 1, signature = 0x12345678), csw(tag = 2))

        val outcome = protocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(outcome.status).isEqualTo(CommandStatus.PASSED)
        assertThat(transport.controlAndInEvents)
            .containsExactlyElementsIn(listOf(Event.In(13)) + resetRecovery + Event.In(13)).inOrder()
    }

    @Test
    fun `a status with another tag triggers reset recovery`() {
        transport.queueIn(csw(tag = 7), csw(tag = 2))

        protocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(1)
    }

    @Test
    fun `a short status triggers reset recovery`() {
        transport.queueIn(csw(tag = 1).copyOf(12), csw(tag = 2))

        protocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(1)
    }

    @Test
    fun `a phase error triggers reset recovery and the command is retried once`() {
        transport.queueIn(csw(tag = 1, status = 2), csw(tag = 2))

        val outcome = protocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(outcome.status).isEqualTo(CommandStatus.PASSED)
        assertThat(transport.controlAndInEvents)
            .containsExactlyElementsIn(listOf(Event.In(13)) + resetRecovery + Event.In(13)).inOrder()
    }

    @Test
    fun `a persistent phase error fails after a bounded number of attempts`() {
        transport.queueIn(csw(tag = 1, status = 2), csw(tag = 2, status = 2), csw(tag = 3))

        assertThrows(UsbStorageException.TransportFailure::class.java) {
            protocol.execute(ByteArray(6), DataDirection.NONE)
        }
        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(BulkOnlyProtocol.MAX_ATTEMPTS)
        assertThat(transport.outWrites).hasSize(BulkOnlyProtocol.MAX_ATTEMPTS)
    }

    @Test
    fun `a command block wrapper that cannot be sent fails after bounded reset recovery`() {
        transport.queueOutResult(-1, 0)

        assertThrows(UsbStorageException.TransportFailure::class.java) {
            protocol.execute(ByteArray(6), DataDirection.NONE)
        }
        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(2)
    }

    @Test
    fun `a clear halt that fails escalates to reset recovery and the command is retried`() {
        transport.queueInFailure()
        transport.queueClearHaltResult(false)
        transport.queueIn(ByteArray(36) { 4 }, csw(tag = 2))
        val buffer = ByteArray(36)

        val outcome = protocol.execute(inquiry, DataDirection.IN, buffer, 0, 36)

        assertThat(outcome).isEqualTo(CommandOutcome(CommandStatus.PASSED, transferred = 36, residue = 0))
        assertThat(buffer.all { it == 4.toByte() }).isTrue()
        assertThat(transport.controlAndInEvents).containsExactlyElementsIn(
            listOf(Event.In(36), Event.ClearHalt(BulkEndpoint.IN)) + resetRecovery + listOf(Event.In(36), Event.In(13)),
        ).inOrder()
    }

    @Test
    fun `a clear halt that keeps failing ends in a transport failure without a retry`() {
        transport.clearHaltSucceeds = false
        transport.queueInFailure()
        transport.queueIn(ByteArray(36), csw(tag = 2))

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            protocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)
        }

        assertThat(failure).hasMessageThat().contains("reset")
        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(1)
        assertThat(transport.outWrites).hasSize(1)
    }

    @Test
    fun `a stall whose clear halt fails on every attempt stops after a bounded number of attempts`() {
        repeat(BulkOnlyProtocol.MAX_ATTEMPTS) {
            transport.queueInFailure()
            transport.queueClearHaltResult(false, true, true)
        }

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            protocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)
        }

        assertThat(failure).hasMessageThat().contains("clearing the IN halt failed")
        assertThat(transport.events.filterIsInstance<Event.Reset>()).hasSize(BulkOnlyProtocol.MAX_ATTEMPTS)
        assertThat(transport.outWrites).hasSize(BulkOnlyProtocol.MAX_ATTEMPTS)
    }

    @Test
    fun `a failing reset recovery stops at once instead of retrying`() {
        transport.resetSucceeds = false
        transport.queueIn(csw(tag = 1, status = 2), csw(tag = 2))

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            protocol.execute(ByteArray(6), DataDirection.NONE)
        }
        assertThat(failure).hasMessageThat().contains("reset")
        assertThat(transport.outWrites).hasSize(1)
    }

    private val logged = mutableListOf<String>()
    private val loggingProtocol = BulkOnlyProtocol(transport, log = { logged += it })
    private val write10 = byteArrayOf(0x2A, 0, 0, 0, 0, 0, 0, 0, 1, 0)

    @Test
    fun `a command block wrapper that cannot be sent is logged and summarised per attempt`() {
        transport.queueOutResult(-1, -1)

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            loggingProtocol.execute(write10, DataDirection.OUT, ByteArray(512), 0, 512)
        }

        val cbwFailure = "CBW: bulkTransfer returned -1 (0 of 31 bytes transferred)"
        assertThat(logged).containsExactly(
            "Command 0x2A attempt 1/2: $cbwFailure; running reset recovery",
            "Command 0x2A attempt 2/2: $cbwFailure; running reset recovery",
        ).inOrder()
        assertThat(failure).hasMessageThat().isEqualTo(
            "Command 0x2A failed after 2 attempts: attempt 1: $cbwFailure; attempt 2: $cbwFailure",
        )
    }

    @Test
    fun `a data-out stall is logged with the bytes moved before it`() {
        transport.queueOutResult(31, 100, -1)
        transport.queueIn(csw(tag = 1, residue = 412, status = 1))

        loggingProtocol.execute(write10, DataDirection.OUT, ByteArray(512), 0, 512)

        assertThat(logged).containsExactly(
            "Command 0x2A attempt 1/2: data-out: bulkTransfer returned -1 (100 of 512 bytes transferred); clearing the OUT halt",
        )
    }

    @Test
    fun `a data-in stall is logged with phase, return value, lengths, opcode and attempt`() {
        transport.queueInFailure()
        transport.queueIn(csw(tag = 1, residue = 36, status = 1))

        loggingProtocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)

        assertThat(logged).containsExactly(
            "Command 0x12 attempt 1/2: data-in: bulkTransfer returned -1 (0 of 36 bytes transferred); clearing the IN halt",
        )
    }

    @Test
    fun `status read failures are logged as CSW problems`() {
        transport.queueInFailure()
        transport.queueIn(csw(tag = 1).copyOf(12), csw(tag = 2))

        loggingProtocol.execute(ByteArray(6), DataDirection.NONE)

        assertThat(logged).containsExactly(
            "Command 0x00 attempt 1/2: CSW: bulkTransfer returned -1 (0 of 13 bytes transferred); clearing the IN halt",
            "Command 0x00 attempt 1/2: CSW: bulkTransfer returned 12 (12 of 13 bytes transferred); running reset recovery",
        ).inOrder()
    }

    @Test
    fun `a failing clear halt is logged and named in the transport failure`() {
        transport.clearHaltSucceeds = false
        transport.queueInFailure()

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            loggingProtocol.execute(inquiry, DataDirection.IN, ByteArray(36), 0, 36)
        }

        assertThat(logged).contains("Command 0x12 attempt 1/2: clear-halt IN failed")
        assertThat(logged).contains("Command 0x12 attempt 1/2: reset: clear-halt IN failed")
        assertThat(failure).hasMessageThat().isEqualTo(
            "Command 0x12 attempt 1/2: reset recovery failed (clear-halt IN failed) after data-in: bulkTransfer " +
                "returned -1 (0 of 36 bytes transferred); clearing the IN halt failed",
        )
    }

    @Test
    fun `a failing reset is logged and its transport failure names the problem that caused it`() {
        transport.resetSucceeds = false
        transport.queueIn(csw(tag = 1, status = 2))

        val failure = assertThrows(UsbStorageException.TransportFailure::class.java) {
            loggingProtocol.execute(ByteArray(6), DataDirection.NONE)
        }

        assertThat(logged).containsExactly(
            "Command 0x00 attempt 1/2: CSW: phase error; running reset recovery",
            "Command 0x00 attempt 1/2: reset: Bulk-Only Mass Storage Reset failed",
        ).inOrder()
        assertThat(failure).hasMessageThat().isEqualTo(
            "Command 0x00 attempt 1/2: reset recovery failed (Bulk-Only Mass Storage Reset failed) after CSW: phase error",
        )
    }
}
