package io.sonora.core.usbstorage.protocol

/**
 * The bulk endpoint pair and class requests of one USB mass-storage interface. It is the only thing the protocol layer
 * needs from Android, so the protocol is tested on the JVM with a fake. Every call blocks: use an IO dispatcher.
 */
interface BulkTransport {

    /** wMaxPacketSize of the bulk IN endpoint; a shorter packet ends a data-in phase early. */
    val inMaxPacketSize: Int

    /** Sends up to [length] bytes; returns the count actually sent (possibly fewer), or -1 on error or STALL. */
    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Receives up to [length] bytes; returns the count received (fewer on a short packet), or -1 on error or STALL. */
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT) on [endpoint]; false when the request failed. */
    fun clearHalt(endpoint: BulkEndpoint): Boolean

    /** Bulk-Only Mass Storage Reset class request (BOT 3.1); false when the request failed. */
    fun massStorageReset(): Boolean

    /** Get Max LUN class request (BOT 3.2): the highest LUN, or null when the device stalls it (single LUN). */
    fun getMaxLun(): Int?
}

enum class BulkEndpoint { IN, OUT }
