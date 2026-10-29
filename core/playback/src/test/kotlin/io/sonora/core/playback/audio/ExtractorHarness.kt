@file:androidx.annotation.OptIn(UnstableApi::class)

package io.sonora.core.playback.audio

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Runs a Media3 [Extractor] over an in-memory file like the player's loader does: real [DefaultExtractorInput]s
 * that hand out at most [maxReadBytes] bytes per read (so partial reads happen), reopened at the requested position
 * on every `RESULT_SEEK`. Collects the format, the seek map and the samples.
 */
internal class ExtractorHarness(
    private val file: ByteArray,
    private val extractor: Extractor,
    private val maxReadBytes: Int = 4_093,
) : ExtractorOutput, TrackOutput {

    var format: Format? = null
    var seekMap: SeekMap? = null
    var tracksEnded = false
    val samples = mutableListOf<Sample>()
    private val pending = ByteArrayOutputStream()

    class Sample(val timeUs: Long, val flags: Int, val data: ByteArray)

    /** Every sample's bytes, in order. */
    val data: ByteArray get() = samples.fold(ByteArray(0)) { all, sample -> all + sample.data }

    fun sniff(): Boolean = extractor.sniff(input(0))

    /** Extracts from [position] (the start, or a seek point after [seekTo]) to the end of the file. */
    fun extractAll(position: Long = 0) {
        if (position == 0L && seekMap == null) extractor.init(this)
        var input = input(position)
        val holder = PositionHolder()
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return
                Extractor.RESULT_SEEK -> input = input(holder.position)
            }
        }
    }

    /** Seeks like the player: the extractor is told the seek point and reading resumes at its position. */
    fun seekTo(timeUs: Long): Long {
        val point = checkNotNull(seekMap).getSeekPoints(timeUs).first
        extractor.seek(point.position, point.timeUs)
        samples.clear()
        extractAll(point.position)
        return point.timeUs
    }

    private fun input(position: Long): DefaultExtractorInput {
        val reader = object : DataReader {
            private var at = position.toInt()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (at >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, maxReadBytes, file.size - at)
                System.arraycopy(file, at, buffer, offset, count)
                at += count
                return count
            }
        }
        return DefaultExtractorInput(reader, position, file.size.toLong())
    }

    override fun track(id: Int, type: Int): TrackOutput = this

    override fun endTracks() {
        tracksEnded = true
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            check(allowEndOfInput)
            return C.RESULT_END_OF_INPUT
        }
        pending.write(buffer, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending.write(buffer)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val bytes = pending.toByteArray()
        check(offset == 0 && size == bytes.size) { "sample of $size bytes with ${bytes.size} pending" }
        samples += Sample(timeUs, flags, bytes)
        pending.reset()
    }
}
