package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.ChunkLogits
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** One log-mel slice stored in a golden `.mel.f32` file: [count] frames from frame [start], frame-major. */
class MelSlice(val name: String, val start: Int, val count: Int, val values: FloatArray)

/**
 * Everything of one `tools/beatthis/goldens` case beyond the aggregated logits that [BeatThisGoldens] loads: the
 * parameters of the synthetic click track and a Kotlin re-implementation of its generator (the formula of
 * `tools/beatthis/synth.py`, "click_track/1"), the stored log-mel slices and the raw per-chunk model outputs.
 */
class BeatThisCase private constructor(
    val name: String,
    val numSamples: Int,
    val frames: Int,
    private val kind: String,
    private val bpm0: Double,
    private val bpm1: Double,
    private val durationSeconds: Double,
    val storedClickSampleIndices: IntArray,
    val probeStart: Int,
    val probe: FloatArray,
    val peak: Double,
    val melSlices: List<MelSlice>,
    val chunkStarts: IntArray,
    val chunkLengths: IntArray,
    val chunks: List<ChunkLogits>,
) {
    /** The beat times of the generator formula, seconds. */
    fun beatTimes(): DoubleArray = when (kind) {
        "constant" -> {
            val period = 60.0 / bpm0
            val count = floor((durationSeconds - TAIL - OFFSET) / period).toInt() + 1
            DoubleArray(count) { OFFSET + it * period }
        }
        "ramp" -> {
            val a = (bpm1 - bpm0) / (120.0 * durationSeconds)
            val b = bpm0 / 60.0
            val times = ArrayList<Double>()
            var k = 0
            while (true) {
                val t = OFFSET + (-b + sqrt(b * b + 4.0 * a * k)) / (2.0 * a)
                if (t > durationSeconds - TAIL) break
                times += t
                k++
            }
            times.toDoubleArray()
        }
        "silence" -> DoubleArray(0)
        else -> error("unknown generator kind $kind")
    }

    /** `floor(t * 22050 + 0.5)` of every beat time. */
    fun clickSampleIndices(): IntArray = beatTimes().let { t -> IntArray(t.size) { floor(t[it] * SAMPLE_RATE + 0.5).toInt() } }

    /** The synthetic audio, 22050 Hz mono, regenerated from the formula in double precision and cast to float. */
    fun audio(): FloatArray {
        val signal = DoubleArray(numSamples)
        val indices = clickSampleIndices()
        for ((k, start) in indices.withIndex()) {
            val amp = if (k % 4 == 0) 0.6 else 0.4
            for (i in 0 until CLICK_LENGTH) {
                if (start + i >= numSamples) break
                signal[start + i] += amp * sin(2.0 * PI * CLICK_HZ * i / SAMPLE_RATE) * exp(-(i.toDouble() / SAMPLE_RATE) / CLICK_TAU)
            }
        }
        return FloatArray(numSamples) { signal[it].toFloat() }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        private const val OFFSET = 0.3
        private const val TAIL = 0.2
        private const val CLICK_HZ = 2000.0
        private const val CLICK_TAU = 0.015
        private const val CLICK_LENGTH = 3307
        const val MEL_BINS = 128

        /**
         * Loads case [name]; with [chunking] (e.g. `chunk300_border24`, see [BeatThisGoldens.APP_CHUNKING]) its chunk
         * starts, lengths and raw outputs come from that subdirectory, made with another chunking. The audio and the
         * mel slices are the same either way.
         */
        fun load(name: String, chunking: String? = null): BeatThisCase {
            val base = BeatThisGoldens.directory()
            val dir = chunking?.let { File(base, it) } ?: base
            val json = File(dir, "$name.json").readText()
            val generator = section(json, "\"generator\"", "\"audio\"")
            val audio = section(json, "\"audio\"", "\"frames\"")
            val mel = section(json, "\"mel\"", "\"logits\"")
            val chunksJson = section(json, "\"chunks\"", "\"postprocess\"")
            val spec = generator.substring(generator.indexOf("\"spec\""), generator.indexOf("\"sampleRate\""))

            val frames = Regex("\"frames\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()
            val slices = readSlices(File(base, "$name.mel.f32"), mel)
            val starts = numbers(chunksJson, "starts").map { it.toInt() }.toIntArray()
            val lengths = numbers(chunksJson, "lengths").map { it.toInt() }.toIntArray()
            val probe = audio.substring(audio.indexOf("\"probe\""))
            return BeatThisCase(
                name = name,
                numSamples = Regex("\"numSamples\"\\s*:\\s*(\\d+)").find(generator)!!.groupValues[1].toInt(),
                frames = frames,
                kind = Regex("\"kind\"\\s*:\\s*\"(\\w+)\"").find(spec)!!.groupValues[1],
                bpm0 = number(spec, "bpm") ?: number(spec, "bpm0") ?: 0.0,
                bpm1 = number(spec, "bpm1") ?: 0.0,
                durationSeconds = number(spec, "duration")!!,
                storedClickSampleIndices = numbers(generator, "clickSampleIndices").map { it.toInt() }.toIntArray(),
                probeStart = Regex("\"start\"\\s*:\\s*(\\d+)").find(probe)!!.groupValues[1].toInt(),
                probe = numbers(probe, "values").map { it.toFloat() }.toFloatArray(),
                peak = number(audio, "peak")!!,
                melSlices = slices,
                chunkStarts = starts,
                chunkLengths = lengths,
                chunks = readChunks(File(dir, "$name.chunks.f32"), lengths),
            )
        }

        /** The 513 x 128 filterbank of `mel_filterbank.f32`, row = FFT bin. */
        fun melFilterbank(): FloatArray {
            val values = floats(File(BeatThisGoldens.directory(), "mel_filterbank.f32"))
            check(values.size == 513 * MEL_BINS) { "mel_filterbank.f32 holds ${values.size} values" }
            return values
        }

        private fun readSlices(file: File, mel: String): List<MelSlice> {
            val all = floats(file)
            val pattern = Regex("\"name\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"start\"\\s*:\\s*(\\d+)\\s*,\\s*\"count\"\\s*:\\s*(\\d+)")
            var at = 0
            val slices = pattern.findAll(mel).map {
                val count = it.groupValues[3].toInt()
                val values = all.copyOfRange(at * MEL_BINS, (at + count) * MEL_BINS)
                at += count
                MelSlice(it.groupValues[1], it.groupValues[2].toInt(), count, values)
            }.toList()
            check(at * MEL_BINS == all.size) { "${file.name}: slices cover $at frames of ${all.size / MEL_BINS}" }
            return slices
        }

        private fun readChunks(file: File, lengths: IntArray): List<ChunkLogits> {
            val all = floats(file)
            check(all.size == 2 * lengths.sum()) { "${file.name}: ${all.size} values for chunks of ${lengths.toList()}" }
            var at = 0
            return lengths.map { length ->
                val beat = all.copyOfRange(at, at + length)
                val downbeat = all.copyOfRange(at + length, at + 2 * length)
                at += 2 * length
                ChunkLogits(beat, downbeat)
            }
        }

        private fun floats(file: File): FloatArray {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        private fun section(text: String, from: String, to: String): String {
            val start = text.indexOf(from)
            return text.substring(start, text.indexOf(to, start + from.length))
        }

        private fun number(text: String, key: String): Double? =
            Regex("\"$key\"\\s*:\\s*(-?[0-9.eE+-]+)").find(text)?.groupValues?.get(1)?.toDouble()

        private fun numbers(text: String, key: String): List<Double> {
            val body = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
        }
    }
}
