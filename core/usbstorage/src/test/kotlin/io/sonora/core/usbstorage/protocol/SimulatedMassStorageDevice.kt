package io.sonora.core.usbstorage.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB stick speaking Bulk-Only Transport and the SCSI commands Sonora uses, backed by a sparse in-memory disk. A
 * failing data-in command stalls bulk IN until the host clears the halt, as real devices do; REQUEST SENSE then reports
 * the failure. Every CDB received is kept in [cdbs].
 */
class SimulatedMassStorageDevice(
    val blockSize: Int = 512,
    val blockCount: Long = 2048,
    override val inMaxPacketSize: Int = 512,
) : BulkTransport {

    var peripheralDeviceType = 0x00
    var writeProtected = false
    var modeSenseSupported = true
    var reportedMaxLun: Int? = 0

    /** TEST UNIT READY answers CHECK CONDITION with [notReadySense] this many times before passing. */
    var notReadyCount = 0
    var notReadySense = SenseData(SenseData.UNIT_ATTENTION, 0x28, 0x00)

    /** The sense SYNCHRONIZE CACHE fails with, or null when it is supported. */
    var synchronizeCacheSense: SenseData? = null

    /** READ of this LBA fails with [readFailureSense]. */
    var failingReadLba: Long? = null
    var readFailureSense = SenseData(SenseData.MEDIUM_ERROR, 0x11, 0x00)

    val cdbs = mutableListOf<ByteArray>()
    val lunsSeen = mutableSetOf<Int>()
    var synchronizeCacheCount = 0

    private val blocks = HashMap<Long, ByteArray>()
    private val pendingIn = ArrayDeque<ByteArray>()
    private var inHalted = false
    private var pendingSense: SenseData? = null
    private var expectedOut: PendingOut? = null

    private class PendingOut(val tag: Int, val cdb: ByteArray, val data: ByteArray, var received: Int = 0)

    fun block(lba: Long): ByteArray = blocks[lba]?.copyOf() ?: ByteArray(blockSize)

    fun opcodes(): List<Int> = cdbs.map { it[0].toInt() and 0xFF }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val out = expectedOut
        if (out != null) {
            val count = minOf(length, out.data.size - out.received)
            buffer.copyInto(out.data, out.received, offset, offset + count)
            out.received += count
            if (out.received == out.data.size) {
                expectedOut = null
                finish(out.tag, residue = 0, status = handleOut(out.cdb, out.data))
            }
            return count
        }
        check(length == BulkOnlyProtocol.CBW_LENGTH && pendingIn.isEmpty()) { "Unexpected bulk out of $length bytes" }
        val cbw = ByteBuffer.wrap(buffer, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        check(cbw.getInt() == BulkOnlyProtocol.CBW_SIGNATURE)
        val tag = cbw.getInt()
        val transferLength = cbw.getInt()
        val dataIn = (cbw.get().toInt() and 0x80) != 0
        lunsSeen += cbw.get().toInt()
        val cdb = ByteArray(cbw.get().toInt()).also { cbw.get(it) }
        cdbs += cdb
        if (!dataIn && transferLength > 0) {
            expectedOut = PendingOut(tag, cdb, ByteArray(transferLength))
        } else {
            handleIn(cdb, transferLength, tag)
        }
        return length
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (inHalted) return -1
        val next = pendingIn.removeFirstOrNull() ?: return -1
        check(next.size <= length) { "Device would overflow a $length byte read with ${next.size} bytes" }
        next.copyInto(buffer, offset)
        return next.size
    }

    override fun clearHalt(endpoint: BulkEndpoint): Boolean {
        if (endpoint == BulkEndpoint.IN) inHalted = false
        return true
    }

    override fun massStorageReset(): Boolean = true

    override fun getMaxLun(): Int? = reportedMaxLun

    private fun handleIn(cdb: ByteArray, transferLength: Int, tag: Int) {
        val opcode = cdb[0].toInt() and 0xFF
        val result: Result = when (opcode) {
            ScsiCommands.TEST_UNIT_READY -> testUnitReady()
            ScsiCommands.REQUEST_SENSE -> Result.Data(senseBytes(pendingSense ?: SenseData(0, 0, 0)))
                .also { pendingSense = null }
            ScsiCommands.INQUIRY -> Result.Data(inquiryBytes())
            ScsiCommands.READ_CAPACITY_10 -> Result.Data(
                ByteBuffer.allocate(8).putInt(minOf(blockCount - 1, 0xFFFFFFFFL).toInt()).putInt(blockSize).array(),
            )
            ScsiCommands.SERVICE_ACTION_IN_16 -> Result.Data(
                ByteBuffer.allocate(32).putLong(blockCount - 1).putInt(blockSize).array(),
            )
            ScsiCommands.MODE_SENSE_6 ->
                if (modeSenseSupported) Result.Data(byteArrayOf(3, 0, if (writeProtected) 0x80.toByte() else 0, 0))
                else Result.Check(SenseData(SenseData.ILLEGAL_REQUEST, 0x24, 0x00))
            ScsiCommands.READ_10, ScsiCommands.READ_16 -> read(cdb)
            ScsiCommands.SYNCHRONIZE_CACHE_10 -> {
                synchronizeCacheCount++
                synchronizeCacheSense?.let { Result.Check(it) } ?: Result.Good
            }
            else -> Result.Check(SenseData(SenseData.ILLEGAL_REQUEST, 0x20, 0x00))
        }
        when (result) {
            is Result.Data -> {
                val data = result.bytes.copyOf(minOf(result.bytes.size, transferLength))
                if (data.isNotEmpty()) pendingIn.addLast(data)
                finish(tag, transferLength - data.size, 0)
            }
            Result.Good -> finish(tag, transferLength, 0)
            is Result.Check -> {
                pendingSense = result.sense
                if (transferLength > 0) inHalted = true
                finish(tag, transferLength, 1)
            }
        }
    }

    private fun handleOut(cdb: ByteArray, data: ByteArray): Int {
        val (lba, count) = lbaAndCount(cdb)
        if (writeProtected) {
            pendingSense = SenseData(SenseData.DATA_PROTECT, 0x27, 0x00)
            return 1
        }
        for (i in 0 until count) blocks[lba + i] = data.copyOfRange(i * blockSize, (i + 1) * blockSize)
        return 0
    }

    private fun testUnitReady(): Result {
        if (notReadyCount == 0) return Result.Good
        notReadyCount--
        return Result.Check(notReadySense)
    }

    private fun read(cdb: ByteArray): Result {
        val (lba, count) = lbaAndCount(cdb)
        if (failingReadLba?.let { it in lba until lba + count } == true) return Result.Check(readFailureSense)
        val data = ByteArray(count * blockSize)
        for (i in 0 until count) block(lba + i).copyInto(data, i * blockSize)
        return Result.Data(data)
    }

    private fun lbaAndCount(cdb: ByteArray): Pair<Long, Int> {
        val buffer = ByteBuffer.wrap(cdb)
        return when (cdb[0].toInt() and 0xFF) {
            ScsiCommands.READ_10, ScsiCommands.WRITE_10 ->
                (buffer.getInt(2).toLong() and 0xFFFFFFFFL) to (buffer.getShort(7).toInt() and 0xFFFF)
            else -> buffer.getLong(2) to buffer.getInt(10)
        }
    }

    private fun finish(tag: Int, residue: Int, status: Int) {
        pendingIn.addLast(ScriptedBulkTransport.csw(tag, residue, status))
    }

    private fun inquiryBytes(): ByteArray = ByteArray(36).also {
        it[0] = peripheralDeviceType.toByte()
        it[1] = 0x80.toByte()
        "SanDisk ".toByteArray().copyInto(it, 8)
        "Cruzer Blade    ".toByteArray().copyInto(it, 16)
        "1.00".toByteArray().copyInto(it, 32)
    }

    private fun senseBytes(sense: SenseData): ByteArray = ByteArray(18).also {
        it[0] = 0x70
        it[2] = sense.senseKey.toByte()
        it[7] = 10
        it[12] = sense.asc.toByte()
        it[13] = sense.ascq.toByte()
    }

    private sealed interface Result {
        class Data(val bytes: ByteArray) : Result
        data object Good : Result
        class Check(val sense: SenseData) : Result
    }
}
