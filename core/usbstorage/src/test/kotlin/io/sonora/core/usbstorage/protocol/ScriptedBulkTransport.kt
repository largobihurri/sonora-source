package io.sonora.core.usbstorage.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A bulk endpoint pair driven by a script: each `bulkIn` returns the next queued response, each `bulkOut` accepts what
 * the next queued out result allows (everything when none is queued). Every call is recorded in [events].
 */
class ScriptedBulkTransport(override val inMaxPacketSize: Int = 512) : BulkTransport {

    sealed interface Event {
        data class Out(val bytes: ByteArray) : Event
        data class In(val requested: Int) : Event
        data class ClearHalt(val endpoint: BulkEndpoint) : Event
        data object Reset : Event
        data object GetMaxLun : Event
    }

    private sealed interface InResponse {
        class Data(val bytes: ByteArray) : InResponse
        data object Failure : InResponse
    }

    val events = mutableListOf<Event>()
    private val inbound = ArrayDeque<InResponse>()
    private val outResults = ArrayDeque<Int>()
    var clearHaltSucceeds = true
    private val clearHaltResults = ArrayDeque<Boolean>()
    var resetSucceeds = true
    var reportedMaxLun: Int? = 0

    val outWrites: List<ByteArray> get() = events.filterIsInstance<Event.Out>().map { it.bytes }

    /** Control requests and bulk-in reads in order, without the bulk-out writes. */
    val controlAndInEvents: List<Event> get() = events.filter { it !is Event.Out }

    fun queueIn(vararg chunks: ByteArray) = chunks.forEach { inbound.addLast(InResponse.Data(it)) }

    fun queueInFailure() = inbound.addLast(InResponse.Failure)

    fun queueOutResult(vararg counts: Int) = counts.forEach { outResults.addLast(it) }

    /** Results for the next clear-halt requests, in order; [clearHaltSucceeds] answers once they run out. */
    fun queueClearHaltResult(vararg results: Boolean) = results.forEach { clearHaltResults.addLast(it) }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val scripted = outResults.removeFirstOrNull()
        if (scripted != null && scripted < 0) return -1
        val accepted = minOf(scripted ?: length, length)
        events += Event.Out(buffer.copyOfRange(offset, offset + accepted))
        return accepted
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        events += Event.In(length)
        return when (val response = inbound.removeFirstOrNull() ?: return -1) {
            InResponse.Failure -> -1
            is InResponse.Data -> {
                val count = minOf(length, response.bytes.size)
                response.bytes.copyInto(buffer, offset, 0, count)
                count
            }
        }
    }

    override fun clearHalt(endpoint: BulkEndpoint): Boolean {
        events += Event.ClearHalt(endpoint)
        return clearHaltResults.removeFirstOrNull() ?: clearHaltSucceeds
    }

    override fun massStorageReset(): Boolean {
        events += Event.Reset
        return resetSucceeds
    }

    override fun getMaxLun(): Int? {
        events += Event.GetMaxLun
        return reportedMaxLun
    }

    companion object {
        fun csw(tag: Int, residue: Int = 0, status: Int = 0, signature: Int = BulkOnlyProtocol.CSW_SIGNATURE): ByteArray =
            ByteBuffer.allocate(BulkOnlyProtocol.CSW_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(signature).putInt(tag).putInt(residue).put(status.toByte()).array()
    }
}
