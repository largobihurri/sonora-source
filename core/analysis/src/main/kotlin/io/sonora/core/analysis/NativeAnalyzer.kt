package io.sonora.core.analysis

import io.sonora.core.domain.analysis.AnalysisException

/**
 * [AnalysisEngine] backed by the native analyser (`libsonora_analysis.so`: qm-dsp tempo, beat, downbeat and key
 * detection). Holds a native handle until [close]; not thread-safe.
 */
class NativeAnalyzer private constructor(
    private var handle: Long,
    private val sampleRateHz: Int,
) : AnalysisEngine {

    private var framesProcessed = 0L

    override fun process(samples: FloatArray, count: Int) {
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        val status = NativeBridge.process(liveHandle(), samples, count)
        if (status != STATUS_OK) throw NativeResults.failure(status, sampleRateHz, framesProcessed)
        framesProcessed += count
    }

    override fun finish(): EngineResult {
        val values = DoubleArray(NativeResults.SIZE)
        val status = NativeBridge.finish(liveHandle(), values)
        if (status != STATUS_OK) throw NativeResults.failure(status, sampleRateHz, framesProcessed)
        return NativeResults.parse(values)
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativeBridge.destroy(current)
        }
    }

    private fun liveHandle(): Long {
        check(handle != 0L) { "NativeAnalyzer is closed" }
        return handle
    }

    companion object {
        private const val STATUS_OK = 0

        /**
         * @throws AnalysisException.UnsupportedSampleRate for rates outside 8..192 kHz.
         * @throws AnalysisException.EngineFailure when the native library is not available for this device's ABI
         *   (the app ships arm64-v8a and x86_64 only).
         */
        fun create(sampleRateHz: Int): NativeAnalyzer {
            val handleOut = LongArray(1)
            val status = try {
                NativeBridge.create(sampleRateHz, handleOut)
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError on first use, NoClassDefFoundError on later ones.
                throw AnalysisException.EngineFailure("native analyser not available on this device", e)
            }
            if (status != STATUS_OK) throw NativeResults.failure(status, sampleRateHz, 0)
            return NativeAnalyzer(handleOut[0], sampleRateHz)
        }
    }
}

/** JNI entry points of `libsonora_analysis.so` (src/main/cpp/jni_bridge.cpp); loads the library on first use. */
internal object NativeBridge {
    init {
        System.loadLibrary("sonora_analysis")
    }

    @JvmStatic external fun create(sampleRateHz: Int, handleOut: LongArray): Int

    @JvmStatic external fun process(handle: Long, samples: FloatArray, count: Int): Int

    /** Fills [resultOut] (size [NativeResults.SIZE]) as [NativeResults.parse] reads it. */
    @JvmStatic external fun finish(handle: Long, resultOut: DoubleArray): Int

    @JvmStatic external fun destroy(handle: Long)
}
