package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.BeatActivations
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the Beat This! golden data of `tools/beatthis/goldens` (float32 little-endian logits plus a JSON per case; see
 * `tools/beatthis/README.md`). The tests need only the aggregated logits, the reference post-processing and the click
 * times, so a few regular expressions stand in for a JSON parser.
 */
class BeatThisGoldens private constructor(
    val activations: BeatActivations,
    val referenceBeatsMs: DoubleArray,
    val referenceDownbeatsMs: DoubleArray,
    val clickTimesMs: DoubleArray,
) {
    companion object {
        val CASES = listOf("click_090", "click_128", "click_174", "ramp_120_124", "click_128_short", "silence")

        /**
         * The subdirectory with the same cases run with the app's chunking (`BeatThisChunking`, 300 frames, border 24;
         * `make_goldens.py --chunk 300 --border 24`): logits and raw chunks only. The top directory has the upstream
         * chunking (1500 frames, border 6).
         */
        const val APP_CHUNKING = "chunk300_border24"

        private const val FPS = 50.0

        /** The committed golden directory, found by walking up from the working directory. */
        internal fun directory(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                val candidate = File(dir, "tools/beatthis/goldens")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
            error("tools/beatthis/goldens not found above ${System.getProperty("user.dir")}")
        }

        /** Loads [case]; with [chunking] (e.g. [APP_CHUNKING]) from that subdirectory. */
        fun load(case: String, chunking: String? = null): BeatThisGoldens {
            val dir = chunking?.let { File(directory(), it) } ?: directory()
            val json = File(dir, "$case.json").readText()
            val frames = Regex("\"frames\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()
            val bytes = File(dir, "$case.logits.f32").readBytes()
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            check(floats.remaining() == 2 * frames) { "$case: ${floats.remaining()} logits for $frames frames" }
            val beat = FloatArray(frames).also { floats.get(it) }
            val downbeat = FloatArray(frames).also { floats.get(it) }

            val postprocess = json.substring(json.indexOf("\"postprocess\""))
            val generator = json.substring(json.indexOf("\"generator\""), json.indexOf("\"audio\""))
            return BeatThisGoldens(
                BeatActivations(beat, downbeat, FPS),
                secondsToMs(array(postprocess, "beatTimes")),
                secondsToMs(array(postprocess, "downbeatTimes")),
                secondsToMs(array(generator, "beatTimes")),
            )
        }

        private fun array(text: String, key: String): DoubleArray {
            val body = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }.toDoubleArray()
        }

        private fun secondsToMs(seconds: DoubleArray) = DoubleArray(seconds.size) { seconds[it] * 1000.0 }
    }
}
