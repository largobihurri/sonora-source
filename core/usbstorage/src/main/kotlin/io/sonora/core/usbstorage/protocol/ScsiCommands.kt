package io.sonora.core.usbstorage.protocol

import io.sonora.core.domain.storage.StorageIdentity
import io.sonora.core.domain.storage.UsbStorageException
import java.nio.ByteBuffer

/** Why a command failed, from REQUEST SENSE (SPC-4 4.5): sense key, additional sense code and qualifier. */
data class SenseData(val senseKey: Int, val asc: Int, val ascq: Int) {

    /** The typed failure a UI can explain: no medium, write protection, or the raw sense otherwise. */
    fun toException(operationCode: Int): UsbStorageException = when {
        senseKey == NOT_READY && asc == ASC_MEDIUM_NOT_PRESENT -> UsbStorageException.NoMedium()
        senseKey == DATA_PROTECT -> UsbStorageException.WriteProtected()
        else -> UsbStorageException.CommandFailed(operationCode, senseKey, asc, ascq)
    }

    companion object {
        const val NO_SENSE = 0x0
        const val NOT_READY = 0x2
        const val MEDIUM_ERROR = 0x3
        const val ILLEGAL_REQUEST = 0x5
        const val UNIT_ATTENTION = 0x6
        const val DATA_PROTECT = 0x7
        const val ASC_MEDIUM_NOT_PRESENT = 0x3A
    }
}

data class InquiryData(val peripheralDeviceType: Int, val removable: Boolean, val identity: StorageIdentity)

/** READ CAPACITY data: the last addressable LBA and the logical block length in bytes. */
data class Capacity(val lastLba: Long, val blockSize: Int)

/** SCSI command blocks (SPC-4, SBC-3) and parsers for their responses. Multi-byte CDB fields are big-endian. */
object ScsiCommands {
    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val INQUIRY = 0x12
    const val MODE_SENSE_6 = 0x1A
    const val READ_CAPACITY_10 = 0x25
    const val READ_10 = 0x28
    const val WRITE_10 = 0x2A
    const val SYNCHRONIZE_CACHE_10 = 0x35
    const val READ_16 = 0x88
    const val WRITE_16 = 0x8A
    const val SERVICE_ACTION_IN_16 = 0x9E
    const val READ_CAPACITY_16_SERVICE_ACTION = 0x10

    const val INQUIRY_LENGTH = 36
    const val SENSE_LENGTH = 18
    const val READ_CAPACITY_10_LENGTH = 8
    const val READ_CAPACITY_16_LENGTH = 32

    /** usb-storage asks for 192 bytes: some sticks fail on smaller or larger all-pages requests (Linux sd/usb quirks). */
    const val MODE_SENSE_LENGTH = 192

    private const val ALL_PAGES = 0x3F
    private const val MAX_LBA_10 = 0xFFFFFFFFL
    private const val MAX_BLOCKS_10 = 0xFFFF
    private const val WRITE_PROTECT_BIT = 0x80

    fun testUnitReady(): ByteArray = ByteArray(6)

    fun requestSense(): ByteArray = sixByte(REQUEST_SENSE, SENSE_LENGTH)

    fun inquiry(): ByteArray = sixByte(INQUIRY, INQUIRY_LENGTH)

    fun modeSense6AllPages(): ByteArray = sixByte(MODE_SENSE_6, MODE_SENSE_LENGTH).also { it[2] = ALL_PAGES.toByte() }

    fun readCapacity10(): ByteArray = ByteArray(10).also { it[0] = READ_CAPACITY_10.toByte() }

    fun readCapacity16(): ByteArray = ByteBuffer.allocate(16)
        .put(SERVICE_ACTION_IN_16.toByte())
        .put(READ_CAPACITY_16_SERVICE_ACTION.toByte())
        .putLong(0)
        .putInt(READ_CAPACITY_16_LENGTH)
        .array()

    /** Flushes the whole medium: LBA 0 and 0 blocks mean "everything" (SBC-3 5.22). */
    fun synchronizeCache10(): ByteArray = ByteArray(10).also { it[0] = SYNCHRONIZE_CACHE_10.toByte() }

    /** READ(10), or READ(16) when [lba] or [blocks] do not fit the 10-byte fields. */
    fun read(lba: Long, blocks: Int): ByteArray = transfer(READ_10, READ_16, lba, blocks)

    /** WRITE(10), or WRITE(16) when [lba] or [blocks] do not fit the 10-byte fields. */
    fun write(lba: Long, blocks: Int): ByteArray = transfer(WRITE_10, WRITE_16, lba, blocks)

    private fun transfer(opcode10: Int, opcode16: Int, lba: Long, blocks: Int): ByteArray {
        require(lba >= 0 && blocks >= 0) { "Negative LBA or block count" }
        return if (lba <= MAX_LBA_10 && blocks <= MAX_BLOCKS_10) {
            ByteBuffer.allocate(10).put(opcode10.toByte()).put(0).putInt(lba.toInt()).put(0)
                .putShort(blocks.toShort()).put(0).array()
        } else {
            ByteBuffer.allocate(16).put(opcode16.toByte()).put(0).putLong(lba).putInt(blocks).put(0).put(0).array()
        }
    }

    private fun sixByte(opcode: Int, allocationLength: Int) = ByteArray(6).also {
        it[0] = opcode.toByte()
        it[4] = allocationLength.toByte()
    }

    /** Fixed (0x70/0x71) or descriptor (0x72/0x73) format sense; null when the data holds no recognisable sense. */
    fun parseSense(data: ByteArray, length: Int): SenseData? = when (data.getOrNull(0)?.toInt()?.and(0x7F)) {
        0x70, 0x71 -> if (length >= 14) SenseData(data[2].toInt() and 0x0F, data[12].u8(), data[13].u8()) else null
        0x72, 0x73 -> if (length >= 4) SenseData(data[1].toInt() and 0x0F, data[2].u8(), data[3].u8()) else null
        else -> null
    }

    /** Standard INQUIRY data (SPC-4 6.4.2); fields the device did not send read as blank. */
    fun parseInquiry(data: ByteArray, length: Int): InquiryData {
        fun ascii(from: Int, to: Int): String {
            val end = minOf(to, length)
            return if (end <= from) "" else String(data, from, end - from, Charsets.US_ASCII).trim()
        }
        return InquiryData(
            peripheralDeviceType = if (length > 0) data[0].toInt() and 0x1F else -1,
            removable = length > 1 && (data[1].toInt() and 0x80) != 0,
            identity = StorageIdentity(vendor = ascii(8, 16), product = ascii(16, 32), revision = ascii(32, 36)),
        )
    }

    fun parseCapacity10(data: ByteArray): Capacity {
        val fields = ByteBuffer.wrap(data)
        return Capacity(lastLba = fields.getInt(0).toLong() and 0xFFFFFFFFL, blockSize = fields.getInt(4))
    }

    fun parseCapacity16(data: ByteArray): Capacity {
        val fields = ByteBuffer.wrap(data)
        return Capacity(lastLba = fields.getLong(0), blockSize = fields.getInt(8))
    }

    /** WP bit of the device-specific parameter in the MODE SENSE(6) header (SBC-3 6.5.1); unknown reads as writable. */
    fun isWriteProtected(modeSenseHeader: ByteArray, length: Int): Boolean =
        length >= 3 && (modeSenseHeader[2].toInt() and WRITE_PROTECT_BIT) != 0

    private fun Byte.u8() = toInt() and 0xFF
}
