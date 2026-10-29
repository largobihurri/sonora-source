package io.sonora.core.beatmodel

import io.sonora.core.djengine.mel.LogMelSink
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/** Where the committed goldens and the host copy of the model (fetched by `tools/beatthis/fetch_model.py`) are. */
object BeatThisFiles {

    /** The goldens made with the upstream chunking (1500 frames, border 6): mel slices, filterbank, logits, chunks. */
    val goldens: File get() = fromRepositoryRoot("tools/beatthis/goldens").also { check(it.isDirectory) { "$it is missing" } }

    /** The logits and raw chunks of the same cases made with the app's chunking (`make_goldens.py --chunk 300 --border 24`). */
    val appChunkingGoldens: File get() = File(goldens, "chunk300_border24").also { check(it.isDirectory) { "$it is missing" } }

    val manifest: File get() = fromRepositoryRoot("tools/beatthis/model.json")

    /** The pinned model, or `null` when `fetch_model.py` has not been run (the tests then skip). */
    val hostModel: File? get() = fromRepositoryRoot("tools/beatthis/build/small0.onnx").takeIf { it.isFile }

    fun fromRepositoryRoot(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "tools/beatthis/model.json").isFile) return File(dir, path)
            dir = dir.parentFile
        }
        error("tools/beatthis not found above ${System.getProperty("user.dir")}")
    }
}

/**
 * One case of `tools/beatthis/goldens`: the synthetic click track ("click_track/1", the formula of `synth.py`) regenerated
 * in Kotlin and turned into its log-mel by [LogMelSink], the raw model output of every chunk the reference ran, and
 * the aggregated logits. The slices stored in `<case>.mel.f32` are not needed here: the whole spectrogram is rebuilt.
 */
class GoldenCase private constructor(
    val name: String,
    val frames: Int,
    private val numSamples: Int,
    private val kind: String,
    private val bpm0: Double,
    private val bpm1: Double,
    private val durationSeconds: Double,
    val chunkStarts: IntArray,
    val chunkLengths: IntArray,
    val chunks: List<ChunkLogits>,
    val beatLogits: FloatArray,
    val downbeatLogits: FloatArray,
) {
    /** The log-mel spectrogram of the regenerated audio (22050 Hz mono through the D1.8 front end). */
    fun mel(): MelSpectrogram {
        val sink = LogMelSink().create()
        val audio = audio()
        sink.accept(audio, 0, audio.size)
        return sink.finish().also { check(it.frameCount == frames) { "$name: ${it.frameCount} frames, golden has $frames" } }
    }

    private fun beatTimes(): DoubleArray = when (kind) {
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

    private fun audio(): FloatArray {
        val signal = DoubleArray(numSamples)
        for ((k, time) in beatTimes().withIndex()) {
            val start = floor(time * SAMPLE_RATE + 0.5).toInt()
            val amp = if (k % 4 == 0) 0.6 else 0.4
            for (i in 0 until CLICK_LENGTH) {
                if (start + i >= numSamples) break
                signal[start + i] += amp * kotlin.math.sin(2.0 * PI * CLICK_HZ * i / SAMPLE_RATE) * exp(-(i.toDouble() / SAMPLE_RATE) / CLICK_TAU)
            }
        }
        return FloatArray(numSamples) { signal[it].toFloat() }
    }

    companion object {
        val NAMES = listOf("click_090", "click_128", "click_174", "ramp_120_124", "click_128_short", "silence")

        private const val SAMPLE_RATE = 22_050
        private const val OFFSET = 0.3
        private const val TAIL = 0.2
        private const val CLICK_HZ = 2000.0
        private const val CLICK_TAU = 0.015
        private const val CLICK_LENGTH = 3307

        /** Loads case [name] from [dir], [BeatThisFiles.goldens] or [BeatThisFiles.appChunkingGoldens]. */
        fun load(name: String, dir: File = BeatThisFiles.goldens): GoldenCase {
            val json = File(dir, "$name.json").readText()
            val generator = section(json, "\"generator\"", "\"audio\"")
            val chunksJson = section(json, "\"chunks\"", "\"postprocess\"")
            val spec = generator.substring(generator.indexOf("\"spec\""), generator.indexOf("\"sampleRate\""))
            val frames = Regex("\"frames\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()
            val lengths = numbers(chunksJson, "lengths").map { it.toInt() }.toIntArray()

            val logits = floats(File(dir, "$name.logits.f32"))
            check(logits.size == 2 * frames) { "$name: ${logits.size} logits for $frames frames" }
            val raw = floats(File(dir, "$name.chunks.f32"))
            check(raw.size == 2 * lengths.sum()) { "$name: ${raw.size} chunk values for chunks of ${lengths.toList()}" }
            var at = 0
            val chunks = lengths.map { length ->
                ChunkLogits(raw.copyOfRange(at, at + length), raw.copyOfRange(at + length, at + 2 * length)).also { at += 2 * length }
            }
            return GoldenCase(
                name = name,
                frames = frames,
                numSamples = Regex("\"numSamples\"\\s*:\\s*(\\d+)").find(generator)!!.groupValues[1].toInt(),
                kind = Regex("\"kind\"\\s*:\\s*\"(\\w+)\"").find(spec)!!.groupValues[1],
                bpm0 = number(spec, "bpm") ?: number(spec, "bpm0") ?: 0.0,
                bpm1 = number(spec, "bpm1") ?: 0.0,
                durationSeconds = number(spec, "duration")!!,
                chunkStarts = numbers(chunksJson, "starts").map { it.toInt() }.toIntArray(),
                chunkLengths = lengths,
                chunks = chunks,
                beatLogits = logits.copyOfRange(0, frames),
                downbeatLogits = logits.copyOfRange(frames, 2 * frames),
            )
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
