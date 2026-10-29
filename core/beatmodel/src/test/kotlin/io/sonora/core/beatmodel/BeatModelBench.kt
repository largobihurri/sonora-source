package io.sonora.core.beatmodel

import io.sonora.core.djmodel.MelSpectrogram
import java.io.File
import java.util.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host timing and peak memory of one full chunk for a few session settings: the app's 300 frames
 * ([BeatThisModel.CHUNK_FRAMES], D1.26) and, for comparison, the upstream 1500 (D1.21's measurements). Each
 * setting runs in a fresh JVM, so the peak resident set (`VmHWM` of `/proc/self/status`) is its own. Not part of the
 * normal run: set `SONORA_BEATMODEL_BENCH=1` (and have `tools/beatthis/build/small0.onnx`). The lines go to the test's
 * standard output. This is a laptop number, not the phone's (D1.24 measures that).
 */
class BeatModelBenchTest {

    @Test
    fun `report the time and peak memory of one full chunk`() {
        assumeTrue("set SONORA_BEATMODEL_BENCH=1 to run", System.getenv("SONORA_BEATMODEL_BENCH") == "1")
        val model = BeatThisFiles.hostModel
        assumeTrue("tools/beatthis/build/small0.onnx is missing", model != null)
        val java = File(System.getProperty("java.home"), "bin/java").path
        // A short classpath: with the whole test classpath the child JVM crashes in the ONNX Runtime native loader
        // (a JVM-side problem seen with 32 or more entries here), and it needs only these.
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
            .filter { Regex("intermediates/classes/debugUnitTest|runtime_library_classes_jar|onnxruntime-1\\.|kotlin-stdlib|djmodel\\.jar").containsMatchIn(it) }
            .joinToString(File.pathSeparator)
        // frames threads arena memoryPattern
        for (settings in listOf(
            "300 2 true false",
            "300 2 true true",
            "300 2 false false",
            "300 1 true false",
            "300 4 true false",
            "1500 2 true false",
        )) {
            val process = ProcessBuilder(
                listOf(java, "-Xmx256m", "-cp", classpath, "io.sonora.core.beatmodel.BeatModelBenchMain", model!!.path) + settings.split(' '),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            println("[exit ${process.waitFor()}] $output")
        }
    }
}

/** The child JVM of [BeatModelBenchTest]: `model frames threads arena memoryPattern`. */
object BeatModelBenchMain {

    private fun status(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong() / 1024

    @JvmStatic
    fun main(args: Array<String>) {
        val frames = args[1].toInt()
        val config = OrtCpuConfig(intraOpThreads = args[2].toInt(), cpuArena = args[3].toBoolean(), memoryPattern = args[4].toBoolean())
        val bytes = File(args[0]).readBytes()
        val before = status("VmRSS")
        val loadStart = System.nanoTime()
        val descriptor = BeatThisModel.descriptor().copy(chunkFrames = maxOf(frames, BeatThisModel.CHUNK_FRAMES))
        val model = OrtCpuBeatModel.create(bytes, descriptor, config)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val afterLoad = status("VmRSS")
        val random = Random(1L)
        val chunk = MelSpectrogram(128, 50.0, FloatArray(frames * 128) { random.nextFloat() * 6f })
        val times = LongArray(8)
        var firstPeak = 0L
        for (i in times.indices) {
            val start = System.nanoTime()
            model.infer(chunk)
            times[i] = (System.nanoTime() - start) / 1_000_000
            if (i == 0) firstPeak = status("VmHWM")
        }
        val afterRuns = status("VmRSS")
        model.close()
        println(
            "frames=$frames threads=${config.intraOpThreads} arena=${config.cpuArena} memoryPattern=${config.memoryPattern}: load ${loadMs} ms, " +
                "chunk ms (first, then 7) ${times.toList()}, RSS before ${before} MB, after load ${afterLoad} MB, " +
                "peak after first chunk ${firstPeak} MB, final peak ${status("VmHWM")} MB, RSS after runs ${afterRuns} MB",
        )
    }
}
