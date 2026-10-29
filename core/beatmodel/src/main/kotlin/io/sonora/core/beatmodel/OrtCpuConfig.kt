package io.sonora.core.beatmodel

/**
 * How the ONNX Runtime session runs on the CPU. The defaults suit a phone that is also playing music: the analysis
 * is background work, so it takes few threads and does not keep idle ones spinning.
 *
 * Host measurements with the app's 300-frame chunks (D1.26; process peak resident set, of which 128 MB is the JVM,
 * the runtime and the loaded model, then 8 chunks): arena on and pattern off 157 MB, 95 ms per chunk; pattern on
 * 157 MB growing to 177 MB, 85 ms; arena off 211 MB peak, 159 MB after, 112 ms; 1 thread 128 ms, 4 threads 93 ms.
 * The defaults stay the best compromise; the numbers in the properties below are D1.21's, for 1500-frame chunks.
 *
 * @property intraOpThreads threads of one operator (matrix products, attention); 2 to 4 is the useful range on a phone
 *   (1 is allowed for tests and for the lowest load).
 * @property cpuArena the ONNX Runtime arena allocator, which keeps freed blocks for reuse and grows in powers of two.
 *   On: one 1500-frame chunk peaks at about 0.8 GB (host) and the memory is reused. Off: every tensor is a malloc,
 *   the peak is about 1.0 GB, a chunk takes about 1.5 times as long, but the resident set falls back to about 0.4 GB
 *   between chunks.
 * @property memoryPattern pre-planned tensor memory for repeated input shapes. Off by default: with the arena on it
 *   made the resident set grow from 0.8 to 1.4 GB over four identical chunks on the host, and gained no time.
 * @property allowSpinning whether idle intra-op threads busy-wait for work (`session.intra_op.allow_spinning`).
 */
data class OrtCpuConfig(
    val intraOpThreads: Int = DEFAULT_THREADS,
    val cpuArena: Boolean = true,
    val memoryPattern: Boolean = false,
    val allowSpinning: Boolean = false,
) {
    init {
        require(intraOpThreads in MIN_THREADS..MAX_THREADS) {
            "intraOpThreads must be $MIN_THREADS..$MAX_THREADS: $intraOpThreads"
        }
    }

    companion object {
        const val DEFAULT_THREADS = 2
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4
    }
}
