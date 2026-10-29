package io.sonora.core.beatmodel

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the bundled Beat This! model on ONNX Runtime's CPU provider, or reports that there is none: `null` when the
 * asset is missing, unreadable, not the pinned file (size or sha256) or not loadable (an ABI without the native
 * library, a corrupt graph), after a warning to [log]. The analyser then falls back to qm-dsp.
 *
 * The CPU is the only accelerator here: a request for another one gets the CPU model, the best available.
 *
 * [limits], when given, are read every time a model is opened (one per track): the session gets the intra-op threads of
 * the performance in force and is created, by [priority], at its thread priority. A change of performance therefore
 * applies to the next track, never in the middle of one (a session is not rebuilt while it is in use).
 *
 * The file is read through [source], at most [BeatModelFile.sizeBytes] + 1 bytes, and its sha256 is checked the first
 * time it loads; an asset cannot change during the life of a process, so later loads skip the hash. Loading runs on
 * [dispatcher] (reading 10 MB and building the session takes a good part of a second on a phone).
 */
class OrtBeatModelFactory(
    private val source: BeatModelSource,
    private val file: BeatModelFile = BeatThisModel.FILE,
    private val config: OrtCpuConfig = OrtCpuConfig(),
    private val log: BeatModelLog = AndroidBeatModelLog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: DjAnalysisBudgetSource? = null,
    private val priority: ThreadPriorityScope = ThreadPriorityScope.NONE,
) : BeatActivationModelFactory {

    @Volatile
    private var verified = false

    override suspend fun create(preferred: Accelerator): BeatActivationModel? = withContext(dispatcher) { load() }

    private fun load(): BeatActivationModel? {
        val bytes = read() ?: return null
        val budget = limits?.current()?.budget
        return try {
            val create = { OrtCpuBeatModel.create(bytes, BeatThisModel.descriptor(file.sha256), configFor(config, limits)) }
            if (budget == null) create() else priority.runAt(budget.threadPriority, create)
        } catch (e: Exception) {
            log.warn("no beat model: ONNX Runtime could not open ${file.assetPath}: ${e.message}", e)
            null
        } catch (e: LinkageError) {
            log.warn("no beat model: the ONNX Runtime native library is not available: ${e.message}", e)
            null
        }
    }

    /** The verified bytes of the model, or `null` after a warning. */
    private fun read(): ByteArray? {
        val bytes = try {
            source.open().use { readBounded(it, file.sizeBytes) }
        } catch (e: IOException) {
            log.warn("no beat model: cannot read ${file.assetPath}: ${e.message}", e)
            return null
        }
        if (bytes == null || bytes.size.toLong() != file.sizeBytes) {
            log.warn("no beat model: ${file.assetPath} is not ${file.sizeBytes} bytes (size mismatch)", null)
            return null
        }
        if (!verified) {
            val actual = sha256(bytes)
            if (actual != file.sha256) {
                log.warn("no beat model: ${file.assetPath} has sha256 $actual, expected ${file.sha256}", null)
                return null
            }
            verified = true
        }
        return bytes
    }

    /** All of [input], or `null` when it holds more than [limit] bytes (reading stops just past the limit). */
    private fun readBounded(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val block = ByteArray(BLOCK_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(block)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > limit) return null
            out.write(block, 0, n)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BLOCK_BYTES = 64 * 1024

        /** [base] with the intra-op threads of the budget in force in [limits] (clamped to what a session accepts), if any. */
        fun configFor(base: OrtCpuConfig, limits: DjAnalysisBudgetSource?): OrtCpuConfig {
            val threads = limits?.current()?.budget?.intraOpThreads ?: return base
            return base.copy(intraOpThreads = threads.coerceIn(OrtCpuConfig.MIN_THREADS, OrtCpuConfig.MAX_THREADS))
        }
    }
}
