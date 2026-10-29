package io.sonora.core.transcoder.ogg

import java.io.OutputStream

/**
 * Writes an Ogg Opus stream (RFC 7845) to [out] from encoded packets, in the order the format requires: the
 * `OpusHead` page alone, the `OpusTags` page(s), then audio starting on a fresh page. The headers are written with
 * the first packet (or at [finish]), so [preSkip] and [tags] may be settled until then.
 *
 * Each audio packet's duration comes from its own TOC byte ([OpusPackets]); the granule position of a page is the
 * number of 48 kHz samples up to the end of its last packet, pre-skip included. [finish] writes the last page with the
 * exact length: `preSkip + realSamples`, so a decoder trims the encoder's padding at the end, but never more than the
 * packets hold. Pages are cut after [maxPageSamples] samples of audio (a second by default).
 */
class OggOpusWriter(
    out: OutputStream,
    serialNumber: Int,
    private val channels: Int,
    private val preSkip: Int,
    private val inputSampleRateHz: Int,
    private val tags: ByteArray,
    private val maxPageSamples: Int = 48_000,
) {
    private val ogg = OggStreamWriter(out, serialNumber)
    private var headersWritten = false
    private var finished = false
    private var pageSamples = 0L

    /** Samples at 48 kHz in the packets written so far. */
    var packetSamples: Long = 0
        private set

    /** Adds one encoded Opus packet. @throws IllegalArgumentException when it is empty or not a valid packet. */
    fun writePacket(packet: ByteArray) {
        check(!finished) { "The stream is finished" }
        val samples = OpusPackets.sampleCount(packet)
        writeHeaders()
        // Cut before the packet, never after it, so the last page always holds the last packet.
        if (pageSamples >= maxPageSamples) {
            ogg.flushPage()
            pageSamples = 0
        }
        packetSamples += samples
        pageSamples += samples
        ogg.writePacket(packet, packetSamples)
    }

    /** Ends the stream; [realSamples] is the audio the encoder was given, without padding. */
    fun finish(realSamples: Long) {
        check(!finished) { "The stream is finished" }
        writeHeaders()
        ogg.finish(minOf(preSkip + realSamples, packetSamples))
        finished = true
    }

    private fun writeHeaders() {
        if (headersWritten) return
        ogg.writePacket(OpusHeaders.head(channels, preSkip, inputSampleRateHz), granulePosition = 0)
        ogg.flushPage()
        ogg.writePacket(tags, granulePosition = 0)
        ogg.flushPage()
        headersWritten = true
    }
}
