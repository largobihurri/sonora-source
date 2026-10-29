package io.sonora.core.beatmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.MelSpectrogram
import io.sonora.core.domain.conversion.PerformanceMode
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class OrtBeatModelFactoryTest {

    private class RecordingLog : BeatModelLog {
        val messages = ArrayList<String>()
        override fun warn(message: String, cause: Throwable?) {
            messages += message
        }
    }

    private val log = RecordingLog()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun factory(source: BeatModelSource, file: BeatModelFile = BeatThisModel.FILE) =
        OrtBeatModelFactory(source, file, OrtCpuConfig(), log, Dispatchers.Unconfined)

    private fun hostBytes(): ByteArray {
        val file = BeatThisFiles.hostModel
        assumeTrue("tools/beatthis/build/small0.onnx is missing (run tools/beatthis/fetch_model.py)", file != null)
        return file!!.readBytes()
    }

    @Test
    fun `the pinned model is opened and runs`() {
        val bytes = hostBytes()

        val model = runBlocking { factory({ ByteArrayInputStream(bytes) }).create() }

        assertThat(model).isNotNull()
        model!!.use {
            assertThat(it.descriptor.id).isEqualTo("beatthis-small0")
            assertThat(it.descriptor.accelerator).isEqualTo(Accelerator.CPU)
            assertThat(it.infer(MelSpectrogram(128, 50.0, FloatArray(128 * 20))).frameCount).isEqualTo(20)
        }
        assertThat(log.messages).isEmpty()
    }

    @Test
    fun `a request for another accelerator gets the CPU model, the best there is`() {
        val bytes = hostBytes()

        val model = runBlocking { factory({ ByteArrayInputStream(bytes) }).create(Accelerator.NPU) }

        assertThat(model).isNotNull()
        model!!.use { assertThat(it.descriptor.accelerator).isEqualTo(Accelerator.CPU) }
    }

    @Test
    fun `a second model can be opened after the first was closed, and both can be open together`() {
        val bytes = hostBytes()
        val factory = factory({ ByteArrayInputStream(bytes) })

        val first = runBlocking { factory.create() }!!
        val second = runBlocking { factory.create() }!!
        first.close()

        assertThat(second.infer(MelSpectrogram(128, 50.0, FloatArray(128 * 5))).frameCount).isEqualTo(5)
        second.close()
    }

    @Test
    fun `every model is opened at the priority of the performance in force and the session is not shared between tracks`() {
        val bytes = hostBytes()
        var limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad())
        val priorities = ArrayList<Int>()
        val scope = ThreadPriorityScope(get = { 0 }, set = { priorities += it })
        val factory = OrtBeatModelFactory(
            { ByteArrayInputStream(bytes) }, BeatThisModel.FILE, OrtCpuConfig(), log, Dispatchers.Unconfined, { limits }, scope,
        )

        val low = runBlocking { factory.create() }!!
        limits = DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, DeviceLoad())
        val high = runBlocking { factory.create() }!!

        // Set for the creation, put back after it: 15 (low), 0, 10 (high, background), 0.
        assertThat(priorities).containsExactly(15, 0, 10, 0).inOrder()
        assertThat(high).isNotSameInstanceAs(low)
        assertThat(low.infer(MelSpectrogram(128, 50.0, FloatArray(128 * 5))).frameCount).isEqualTo(5)
        assertThat(high.infer(MelSpectrogram(128, 50.0, FloatArray(128 * 5))).frameCount).isEqualTo(5)
        low.close()
        high.close()
    }

    @Test
    fun `a missing asset gives no model and says so`() {
        val model = runBlocking { factory({ throw FileNotFoundException("beatmodel/small0.onnx") }).create() }

        assertThat(model).isNull()
        assertThat(log.messages).hasSize(1)
        assertThat(log.messages.single()).contains("beatmodel/small0.onnx")
    }

    @Test
    fun `an unreadable asset gives no model`() {
        val model = runBlocking { factory({ throw IOException("disk") }).create() }

        assertThat(model).isNull()
        assertThat(log.messages).hasSize(1)
    }

    @Test
    fun `a file with another sha256 gives no model and says so`() {
        val tampered = "not the pinned model".toByteArray()
        val file = BeatModelFile(BeatThisModel.ASSET_PATH, BeatThisModel.SHA256, tampered.size.toLong())

        val model = runBlocking { factory({ ByteArrayInputStream(tampered) }, file).create() }

        assertThat(model).isNull()
        assertThat(log.messages.single()).contains("sha256")
    }

    @Test
    fun `a flipped bit in the real model is caught`() {
        val bytes = hostBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }

        val model = runBlocking { factory({ ByteArrayInputStream(bytes) }).create() }

        assertThat(model).isNull()
        assertThat(log.messages.single()).contains("sha256")
    }

    @Test
    fun `a file longer than the pinned size is refused without reading it all`() {
        var read = 0L
        val endless = BeatModelSource {
            object : java.io.InputStream() {
                override fun read(): Int {
                    read++
                    return 0
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    read += len
                    return len
                }
            }
        }

        val model = runBlocking { factory(endless).create() }

        assertThat(model).isNull()
        assertThat(log.messages.single()).contains("size")
        assertThat(read).isLessThan(BeatThisModel.SIZE_BYTES + 4_000_000)
    }

    @Test
    fun `a file shorter than the pinned size is refused`() {
        val short = ByteArray(1_000)
        val model = runBlocking { factory({ ByteArrayInputStream(short) }).create() }

        assertThat(model).isNull()
        assertThat(log.messages.single()).contains("size")
    }

    @Test
    fun `bytes that match their digest but are not a model give no model and say so`() {
        val junk = ByteArray(2_048) { (it * 31).toByte() }
        val file = BeatModelFile("junk.onnx", sha256(junk), junk.size.toLong())

        val model = runBlocking { factory({ ByteArrayInputStream(junk) }, file).create() }

        assertThat(model).isNull()
        assertThat(log.messages).hasSize(1)
        assertThat(log.messages.single()).contains("junk.onnx")
    }
}
