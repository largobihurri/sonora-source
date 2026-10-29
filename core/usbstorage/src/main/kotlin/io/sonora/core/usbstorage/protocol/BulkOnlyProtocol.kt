package io.sonora.core.usbstorage.protocol

import io.sonora.core.domain.storage.UsbStorageException
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DataDirection { NONE, IN, OUT }

enum class CommandStatus { PASSED, FAILED }

/** A completed command: its CSW status, the data bytes moved and the residue the device reported. */
data class CommandOutcome(val status: CommandStatus, val transferred: Int, val residue: Int)

/**
 * USB Mass Storage Class Bulk-Only Transport 1.0: wraps each SCSI command block in a CBW, moves the data phase and
 * checks the CSW. A data-phase STALL clears the halt and still reads the status (BOT 6.7.2/6.7.3). A malformed or
 * mismatched CSW, a phase error or a broken bulk pipe runs reset recovery (BOT 5.3.4) and retries the command, at most
 * [MAX_ATTEMPTS] times in total. Not thread-safe: callers serialise commands. Blocking: call on an IO dispatcher.
 *
 * Every failure goes to [log] with its phase (CBW, data-in, data-out, CSW, clear-halt, reset), what `bulkTransfer`
 * returned, the bytes requested and moved, the opcode and the attempt; a [UsbStorageException.TransportFailure]
 * message carries the same summary.
 */
class BulkOnlyProtocol(
    private val transport: BulkTransport,
    private val lun: Int = 0,
    private val log: (String) -> Unit = {},
) {
    private var nextTag = 1

    /** A command that cannot complete without reset recovery; never escapes [execute]. */
    private class RecoveryNeeded(message: String) : Exception(message)

    /** Which command and attempt a failure belongs to, as logs and failure messages name it. */
    private class Attempt(val opcode: Int, val number: Int) {
        override fun toString() = "Command 0x%02X attempt %d/%d".format(opcode, number, MAX_ATTEMPTS)
    }

    /**
     * Runs [cdb], moving up to [length] bytes of [buffer] from [offset] in [direction]. A CSW status of "failed" is
     * returned, not thrown: the caller asks REQUEST SENSE why. Throws [UsbStorageException.TransportFailure] when the
     * transport stays broken after recovery.
     */
    fun execute(
        cdb: ByteArray,
        direction: DataDirection,
        buffer: ByteArray = ByteArray(0),
        offset: Int = 0,
        length: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): CommandOutcome {
        require(cdb.size in 1..MAX_CDB_LENGTH) { "A command block holds 1-$MAX_CDB_LENGTH bytes, not ${cdb.size}" }
        require(length == 0 || direction != DataDirection.NONE) { "A data phase needs a direction" }
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Data range outside the buffer" }
        val opcode = cdb[0].toInt() and 0xFF
        val problems = mutableListOf<String>()
        for (number in 1..MAX_ATTEMPTS) {
            val attempt = Attempt(opcode, number)
            try {
                return attempt(cdb, direction, buffer, offset, length, timeoutMs, attempt)
            } catch (problem: RecoveryNeeded) {
                val summary = problem.message.orEmpty()
                problems += "attempt $number: $summary"
                log("$attempt: $summary; running reset recovery")
                resetRecovery(attempt, summary)
            }
        }
        throw UsbStorageException.TransportFailure(
            "Command 0x%02X failed after %d attempts: %s".format(opcode, MAX_ATTEMPTS, problems.joinToString("; ")),
        )
    }

    private fun attempt(
        cdb: ByteArray,
        direction: DataDirection,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
        attempt: Attempt,
    ): CommandOutcome {
        val tag = nextTag++
        val cbw = encodeCbw(tag, length, direction, lun, cdb)
        sendCbw(cbw, timeoutMs)

        val transferred = when {
            length == 0 -> 0
            direction == DataDirection.IN -> receiveData(buffer, offset, length, timeoutMs, attempt)
            else -> sendData(buffer, offset, length, timeoutMs, attempt)
        }

        val csw = readStatus(timeoutMs, attempt)
        val fields = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = fields.getInt(0)
        if (signature != CSW_SIGNATURE) throw RecoveryNeeded("CSW: bad signature 0x%08X".format(signature))
        if (fields.getInt(4) != tag) throw RecoveryNeeded("CSW: tag ${fields.getInt(4)} for command tag $tag")
        val residue = fields.getInt(8)
        return when (val status = csw[12].toInt() and 0xFF) {
            STATUS_PASSED -> CommandOutcome(CommandStatus.PASSED, transferred, residue)
            STATUS_FAILED -> CommandOutcome(CommandStatus.FAILED, transferred, residue)
            else -> throw RecoveryNeeded(if (status == STATUS_PHASE_ERROR) "CSW: phase error" else "CSW: status $status")
        }
    }

    /** Data-in until [length] bytes, a short packet, or a STALL (cleared so the CSW can follow). */
    private fun receiveData(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int, attempt: Attempt): Int {
        val packetSize = transport.inMaxPacketSize.coerceAtLeast(1)
        var total = 0
        while (total < length) {
            val count = transport.bulkIn(buffer, offset + total, length - total, timeoutMs)
            if (count < 0) {
                clearHaltOrRecover(BulkEndpoint.IN, transferProblem("data-in", count, total, length), attempt)
                break
            }
            total += count
            if (count == 0 || count % packetSize != 0) break
        }
        return total
    }

    /** Data-out until [length] bytes or a STALL (cleared so the CSW can follow). */
    private fun sendData(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int, attempt: Attempt): Int {
        var total = 0
        while (total < length) {
            val count = transport.bulkOut(buffer, offset + total, length - total, timeoutMs)
            if (count <= 0) {
                clearHaltOrRecover(BulkEndpoint.OUT, transferProblem("data-out", count, total, length), attempt)
                break
            }
            total += count
        }
        return total
    }

    /** Reads the 13-byte CSW, retrying once after clearing a STALL on bulk IN (BOT 6.7.2 / figure 2). */
    private fun readStatus(timeoutMs: Int, attempt: Attempt): ByteArray {
        val csw = ByteArray(CSW_LENGTH)
        var count = transport.bulkIn(csw, 0, CSW_LENGTH, timeoutMs)
        if (count < 0) {
            clearHaltOrRecover(BulkEndpoint.IN, transferProblem("CSW", count, 0, CSW_LENGTH), attempt)
            count = transport.bulkIn(csw, 0, CSW_LENGTH, timeoutMs)
        }
        if (count != CSW_LENGTH) throw RecoveryNeeded(transferProblem("CSW", count, maxOf(count, 0), CSW_LENGTH))
        return csw
    }

    private fun sendCbw(cbw: ByteArray, timeoutMs: Int) {
        var total = 0
        while (total < cbw.size) {
            val count = transport.bulkOut(cbw, total, cbw.size - total, timeoutMs)
            if (count <= 0) throw RecoveryNeeded(transferProblem("CBW", count, total, cbw.size))
            total += count
        }
    }

    /** Logs [problem], a STALL or failed transfer on [endpoint], and clears its halt; reset recovery if that fails. */
    private fun clearHaltOrRecover(endpoint: BulkEndpoint, problem: String, attempt: Attempt) {
        log("$attempt: $problem; clearing the $endpoint halt")
        if (!transport.clearHalt(endpoint)) {
            log("$attempt: clear-halt $endpoint failed")
            throw RecoveryNeeded("$problem; clearing the $endpoint halt failed")
        }
    }

    /**
     * Bulk-Only Mass Storage Reset, then clear both halts (BOT 5.3.4), after [cause]. Failing here means the device is
     * unreachable.
     */
    private fun resetRecovery(attempt: Attempt, cause: String) {
        val failedStep = when {
            !transport.massStorageReset() -> "Bulk-Only Mass Storage Reset failed"
            !transport.clearHalt(BulkEndpoint.IN) -> "clear-halt IN failed"
            !transport.clearHalt(BulkEndpoint.OUT) -> "clear-halt OUT failed"
            else -> return
        }
        log("$attempt: reset: $failedStep")
        throw UsbStorageException.TransportFailure("$attempt: reset recovery failed ($failedStep) after $cause")
    }

    private fun transferProblem(phase: String, returned: Int, transferred: Int, requested: Int) =
        "$phase: bulkTransfer returned $returned ($transferred of $requested bytes transferred)"

    companion object {
        const val CBW_LENGTH = 31
        const val CSW_LENGTH = 13
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355
        const val MAX_CDB_LENGTH = 16
        const val MAX_ATTEMPTS = 2
        const val DEFAULT_TIMEOUT_MS = 5_000

        private const val DIRECTION_IN = 0x80
        private const val STATUS_PASSED = 0
        private const val STATUS_FAILED = 1
        private const val STATUS_PHASE_ERROR = 2

        /** The 31-byte Command Block Wrapper (BOT 5.1), little-endian. */
        fun encodeCbw(tag: Int, transferLength: Int, direction: DataDirection, lun: Int, cdb: ByteArray): ByteArray =
            ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CBW_SIGNATURE)
                .putInt(tag)
                .putInt(transferLength)
                .put((if (direction == DataDirection.IN) DIRECTION_IN else 0).toByte())
                .put((lun and 0x0F).toByte())
                .put(cdb.size.toByte())
                .put(cdb)
                .array()
    }
}
