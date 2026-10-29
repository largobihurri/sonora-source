package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What one pipeline run on one reference track produced, kept so the tuning round can refit grids and keys under
 * other parameters without decoding and running the model again: the model's beat [candidates] (what the grid fitter
 * saw), the onset [envelope] (what the phase refiner saw), the [chroma] (what the key classifier saw), the [waveform],
 * and the pipeline's own [pipelineGrid] and [pipelineKey] under the defaults. Derived features only: no audio.
 * `null` parts mean the pipeline never reached that stage (no model output, no onset sink, no chroma frames).
 */
data class TrackCapture(
    val path: String,
    val format: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Double,
    val candidates: BeatCandidates?,
    val envelope: OnsetEnvelope?,
    val chroma: ChromaFrames?,
    val waveform: BandWaveform,
    val pipelineGrid: DjBeatGrid?,
    val pipelineKey: KeyEstimate?,
    val beatSourceId: String,
    val stageMs: Map<AnalysisStage, Long>,
    val wallMs: Long,
    /** CPU package temperature when the analysis started, `NaN` when unknown. */
    val temperatureC: Double = Double.NaN,
)

/**
 * The cache file format of a [TrackCapture] (big-endian, `java.io.Data*`): magic `SCAP`, version, then the fields in
 * declaration order, each optional part behind a presence byte. Local to the calibration cache in `build/`; a new
 * layout gets a new [VERSION] and old files are simply recomputed.
 */
object CaptureCodec {

    private const val MAGIC = 0x53434150 // "SCAP"
    const val VERSION: Int = 1

    fun write(capture: TrackCapture, out: OutputStream) {
        val data = DataOutputStream(out.buffered())
        with(data) {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeUTF(capture.path)
            writeUTF(capture.format)
            writeInt(capture.sampleRateHz)
            writeInt(capture.channelCount)
            writeDouble(capture.durationMs)
            optional(capture.candidates) {
                doubles(it.beatsMs())
                doubles(it.downbeatsMs())
                doubles(it.downbeatProbabilities())
            }
            optional(capture.envelope) {
                writeDouble(it.framesPerSecond)
                floats(it.values())
            }
            optional(capture.chroma) { chroma ->
                writeDouble(chroma.framesPerSecond)
                val bins = ChromaFrames.BINS_PER_FRAME
                floats(FloatArray(chroma.frameCount * bins) { chroma[it / bins, it % bins] })
            }
            with(capture.waveform) {
                writeInt(version)
                bytes(low())
                bytes(mid())
                bytes(high())
                bytes(peak())
            }
            optional(capture.pipelineGrid) { grid ->
                writeInt(grid.segments.size)
                for (segment in grid.segments) {
                    writeDouble(segment.startMs)
                    writeDouble(segment.bpm)
                    writeInt(segment.beatInBarAtStart)
                }
            }
            optional(capture.pipelineKey) {
                writeInt(it.key.index)
                writeDouble(it.strength)
                writeUTF(it.method)
            }
            writeUTF(capture.beatSourceId)
            writeInt(capture.stageMs.size)
            for ((stage, ms) in capture.stageMs) {
                writeUTF(stage.name)
                writeLong(ms)
            }
            writeLong(capture.wallMs)
            writeDouble(capture.temperatureC)
            flush()
        }
    }

    /** @throws IOException for foreign, truncated or newer data. */
    fun read(input: InputStream): TrackCapture {
        val data = DataInputStream(input.buffered())
        try {
            with(data) {
                if (readInt() != MAGIC) throw IOException("Not a calibration capture")
                val version = readInt()
                if (version != VERSION) throw IOException("Capture version $version, expected $VERSION")
                val path = readUTF()
                val format = readUTF()
                val rate = readInt()
                val channels = readInt()
                val duration = readDouble()
                val candidates = optional { BeatCandidates(doubles(), doubles(), doubles()) }
                val envelope = optional {
                    val fps = readDouble()
                    OnsetEnvelope(floats(), fps)
                }
                val chroma = optional {
                    val fps = readDouble()
                    ChromaFrames(floats(), fps)
                }
                val waveformVersion = readInt()
                val waveform = BandWaveform(bytes(), bytes(), bytes(), bytes(), waveformVersion)
                val grid = optional {
                    DjBeatGrid(List(count()) { TempoSegment(readDouble(), readDouble(), readInt()) })
                }
                val key = optional {
                    val musicalKey = MusicalKey.fromIndex(readInt()) ?: throw IOException("Bad key index")
                    KeyEstimate(musicalKey, readDouble(), readUTF())
                }
                val source = readUTF()
                val stages = LinkedHashMap<AnalysisStage, Long>()
                repeat(count()) { stages[AnalysisStage.valueOf(readUTF())] = readLong() }
                val wall = readLong()
                val temperature = readDouble()
                return TrackCapture(
                    path, format, rate, channels, duration, candidates, envelope, chroma, waveform, grid, key, source, stages, wall, temperature,
                )
            }
        } catch (e: EOFException) {
            throw IOException("Truncated capture", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Corrupt capture: ${e.message}", e)
        }
    }

    private inline fun <T> DataOutputStream.optional(value: T?, body: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) body(value)
    }

    private inline fun <T> DataInputStream.optional(body: DataInputStream.() -> T): T? = if (readBoolean()) body() else null

    private fun DataOutputStream.doubles(values: DoubleArray) {
        writeInt(values.size)
        for (v in values) writeDouble(v)
    }

    private fun DataOutputStream.floats(values: FloatArray) {
        writeInt(values.size)
        for (v in values) writeFloat(v)
    }

    private fun DataOutputStream.bytes(values: ByteArray) {
        writeInt(values.size)
        write(values)
    }

    private fun DataInputStream.count(): Int {
        val n = readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("Bad length $n")
        return n
    }

    private fun DataInputStream.doubles(): DoubleArray {
        val n = count()
        return DoubleArray(n) { readDouble() }
    }

    private fun DataInputStream.floats(): FloatArray {
        val n = count()
        return FloatArray(n) { readFloat() }
    }

    private fun DataInputStream.bytes(): ByteArray {
        val out = ByteArray(count())
        readFully(out)
        return out
    }

    /** Far above any real track (a 24 h envelope at 500 frames/s is 43 M), small enough to stop a corrupt length. */
    private const val MAX_COUNT = 100_000_000
}
