package io.sonora.core.usbstorage.protocol

import io.sonora.core.domain.storage.BlockDevice
import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbStorageException

/**
 * A SCSI direct-access block device (SBC-3) on LUN 0 of a Bulk-Only mass-storage interface. Reads and writes are split
 * into commands of at most [MAX_TRANSFER_BYTES]. Failures are [UsbStorageException]s. Calls block and are serialised:
 * run them on an IO dispatcher.
 */
class ScsiBlockDevice private constructor(
    private val bot: BulkOnlyProtocol,
    private val log: (String) -> Unit,
    capacity: Capacity,
    val identity: StorageIdentity,
    val isWriteProtected: Boolean,
) : BlockDevice {

    override val blockSize: Int = capacity.blockSize
    override val blockCount: Long = capacity.lastLba + 1

    private val blocksPerCommand = maxOf(1, MAX_TRANSFER_BYTES / blockSize)
    private val lock = Any()

    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = synchronized(lock) {
        checkRange(lba, buffer, offset, blocks)
        forEachChunk(lba, offset, blocks) { chunkLba, chunkOffset, chunkBlocks ->
            transferData(ScsiCommands.read(chunkLba, chunkBlocks), DataDirection.IN, buffer, chunkOffset, chunkBlocks)
        }
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) = synchronized(lock) {
        checkRange(lba, buffer, offset, blocks)
        if (blocks > 0 && isWriteProtected) throw UsbStorageException.WriteProtected()
        forEachChunk(lba, offset, blocks) { chunkLba, chunkOffset, chunkBlocks ->
            transferData(ScsiCommands.write(chunkLba, chunkBlocks), DataDirection.OUT, buffer, chunkOffset, chunkBlocks)
        }
    }

    /** SYNCHRONIZE CACHE; a device without a cache command (ILLEGAL REQUEST) has nothing to flush. */
    override fun flush() = synchronized(lock) {
        val sense = bot.command(ScsiCommands.synchronizeCache10(), timeoutMs = FLUSH_TIMEOUT_MS) ?: return@synchronized
        if (sense.senseKey == SenseData.ILLEGAL_REQUEST) {
            log("SYNCHRONIZE CACHE unsupported ($sense); nothing to flush")
            return@synchronized
        }
        throw sense.toException(ScsiCommands.SYNCHRONIZE_CACHE_10)
    }

    private fun checkRange(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        require(blocks >= 0 && lba >= 0 && lba <= blockCount - blocks) {
            "Blocks $lba+$blocks outside the device's $blockCount blocks"
        }
        require(offset >= 0 && offset.toLong() + blocks.toLong() * blockSize <= buffer.size) {
            "Buffer of ${buffer.size} bytes cannot hold $blocks blocks from offset $offset"
        }
    }

    private inline fun forEachChunk(lba: Long, offset: Int, blocks: Int, transfer: (Long, Int, Int) -> Unit) {
        var done = 0
        while (done < blocks) {
            val count = minOf(blocksPerCommand, blocks - done)
            transfer(lba + done, offset + done * blockSize, count)
            done += count
        }
    }

    private fun transferData(cdb: ByteArray, direction: DataDirection, buffer: ByteArray, offset: Int, blocks: Int) {
        val length = blocks * blockSize
        val outcome = bot.execute(cdb, direction, buffer, offset, length, DATA_TIMEOUT_MS)
        val opcode = cdb[0].toInt() and 0xFF
        if (outcome.status == CommandStatus.FAILED) throw bot.requestSense().toException(opcode)
        if (outcome.transferred != length || outcome.residue != 0) {
            throw UsbStorageException.TransportFailure(
                "Command 0x%02X moved %d of %d bytes (residue %d)".format(opcode, outcome.transferred, length, outcome.residue),
            )
        }
    }

    companion object {
        /** Bytes per READ/WRITE command: small enough for any stick's buffers, large enough for USB 2.0 throughput. */
        const val MAX_TRANSFER_BYTES = 64 * 1024
        const val TEST_UNIT_READY_ATTEMPTS = 6

        private const val FIRST_BACKOFF_MS = 100L
        private const val MAX_BACKOFF_MS = 1_600L

        /**
         * Per-transfer wait for READ and WRITE, as Linux's sd driver uses. A SATA SSD behind a USB bridge can hold a
         * transfer for several seconds (waking up, or a DRAM-less drive flushing its cache); giving up after 5 s and
         * resetting the bridge interrupts it again, so the format never gets past the stall.
         */
        const val DATA_TIMEOUT_MS = 30_000

        /** Writing back a large cache to slow flash can take far longer than an ordinary command. */
        private const val FLUSH_TIMEOUT_MS = 60_000

        private const val DIRECT_ACCESS_BLOCK_DEVICE = 0x00
        private const val SIMPLIFIED_DIRECT_ACCESS_DEVICE = 0x0E

        /**
         * Brings LUN 0 up: INQUIRY, TEST UNIT READY (retried with backoff while the unit reports UNIT ATTENTION or is
         * becoming ready), READ CAPACITY(10), or (16) when the device is too large for it, and MODE SENSE(6) for the
         * write-protect bit. [sleep] waits between TEST UNIT READY attempts.
         */
        fun open(
            transport: BulkTransport,
            sleep: (Long) -> Unit = { Thread.sleep(it) },
            log: (String) -> Unit = {},
        ): ScsiBlockDevice {
            transport.getMaxLun()?.takeIf { it > 0 }?.let { log("Device has ${it + 1} logical units; using LUN 0") }
            val bot = BulkOnlyProtocol(transport, lun = 0, log = log)

            val inquiry = bot.inquiry()
            if (inquiry.peripheralDeviceType != DIRECT_ACCESS_BLOCK_DEVICE &&
                inquiry.peripheralDeviceType != SIMPLIFIED_DIRECT_ACCESS_DEVICE
            ) {
                throw UsbStorageException.UnsupportedDevice(
                    "SCSI peripheral device type 0x%02X is not a block device".format(inquiry.peripheralDeviceType),
                )
            }
            bot.waitUntilReady(sleep)
            val capacity = bot.readCapacity()
            if (capacity.blockSize <= 0) {
                throw UsbStorageException.UnsupportedDevice("Reported block size ${capacity.blockSize}")
            }
            val writeProtected = bot.readWriteProtect(log)
            return ScsiBlockDevice(bot, log, capacity, inquiry.identity, writeProtected)
        }

        /** Runs a command without data or with data-in; returns null when it passed, else the sense explaining why. */
        private fun BulkOnlyProtocol.command(
            cdb: ByteArray,
            buffer: ByteArray = ByteArray(0),
            timeoutMs: Int = BulkOnlyProtocol.DEFAULT_TIMEOUT_MS,
        ): SenseData? {
            val direction = if (buffer.isEmpty()) DataDirection.NONE else DataDirection.IN
            val outcome = execute(cdb, direction, buffer, 0, buffer.size, timeoutMs)
            return if (outcome.status == CommandStatus.PASSED) null else requestSense()
        }

        private fun BulkOnlyProtocol.requestSense(): SenseData {
            val data = ByteArray(ScsiCommands.SENSE_LENGTH)
            val outcome = execute(ScsiCommands.requestSense(), DataDirection.IN, data, 0, data.size)
            if (outcome.status != CommandStatus.PASSED) {
                throw UsbStorageException.TransportFailure("REQUEST SENSE failed")
            }
            return ScsiCommands.parseSense(data, outcome.transferred) ?: SenseData(SenseData.NO_SENSE, 0, 0)
        }

        private fun BulkOnlyProtocol.dataIn(cdb: ByteArray, length: Int): Pair<ByteArray, Int> {
            val data = ByteArray(length)
            val outcome = execute(cdb, DataDirection.IN, data, 0, length)
            if (outcome.status == CommandStatus.FAILED) throw requestSense().toException(cdb[0].toInt() and 0xFF)
            return data to outcome.transferred
        }

        private fun BulkOnlyProtocol.inquiry(): InquiryData {
            val (data, length) = dataIn(ScsiCommands.inquiry(), ScsiCommands.INQUIRY_LENGTH)
            return ScsiCommands.parseInquiry(data, length)
        }

        private fun BulkOnlyProtocol.waitUntilReady(sleep: (Long) -> Unit) {
            var backoff = FIRST_BACKOFF_MS
            for (attempt in 1..TEST_UNIT_READY_ATTEMPTS) {
                val sense = command(ScsiCommands.testUnitReady()) ?: return
                val transient = sense.senseKey == SenseData.UNIT_ATTENTION ||
                    (sense.senseKey == SenseData.NOT_READY && sense.asc != SenseData.ASC_MEDIUM_NOT_PRESENT)
                if (!transient || attempt == TEST_UNIT_READY_ATTEMPTS) {
                    throw sense.toException(ScsiCommands.TEST_UNIT_READY)
                }
                sleep(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
            }
        }

        private fun BulkOnlyProtocol.readCapacity(): Capacity {
            val (data10, length10) = dataIn(ScsiCommands.readCapacity10(), ScsiCommands.READ_CAPACITY_10_LENGTH)
            if (length10 < ScsiCommands.READ_CAPACITY_10_LENGTH) {
                throw UsbStorageException.TransportFailure("READ CAPACITY(10) returned $length10 bytes")
            }
            val capacity = ScsiCommands.parseCapacity10(data10)
            if (capacity.lastLba != 0xFFFFFFFFL) return capacity
            val (data16, length16) = dataIn(ScsiCommands.readCapacity16(), ScsiCommands.READ_CAPACITY_16_LENGTH)
            if (length16 < 12) throw UsbStorageException.TransportFailure("READ CAPACITY(16) returned $length16 bytes")
            return ScsiCommands.parseCapacity16(data16)
        }

        /** Some sticks reject MODE SENSE; they are treated as writable, and a protected one then fails its writes. */
        private fun BulkOnlyProtocol.readWriteProtect(log: (String) -> Unit): Boolean {
            val data = ByteArray(ScsiCommands.MODE_SENSE_LENGTH)
            val outcome = execute(ScsiCommands.modeSense6AllPages(), DataDirection.IN, data, 0, data.size)
            if (outcome.status == CommandStatus.FAILED) {
                log("MODE SENSE(6) rejected (${requestSense()}); assuming the medium is writable")
                return false
            }
            return ScsiCommands.isWriteProtected(data, outcome.transferred)
        }
    }
}
