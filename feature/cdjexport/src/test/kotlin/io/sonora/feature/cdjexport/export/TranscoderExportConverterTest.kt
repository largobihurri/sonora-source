package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The app's export converter over a fake engine: what it hands out, and that nothing of a failed conversion stays. */
class TranscoderExportConverterTest {

    private val cache = Files.createTempDirectory("export-cache").toFile()
    private val folder = File(cache, "export-conversions")
    private val engine = FakeEngine()
    private val converter = TranscoderExportConverter(engine, { folder }, { it.toURI().toString() })
    private val flac = SourceFile("content://picked/song.flac", "song.flac", 1000, SourceAudio(AudioContainer.FLAC, 24, 96_000, 2))

    @Test
    fun `a conversion is a plain AIFF named after the source, deleted when closed`() = runTest {
        val progress = mutableListOf<Float>()

        val converted = converter.convert(flac, DeviceProfile.XDJ_700) { progress += it }

        assertThat(converted.file.fileName).isEqualTo("song.aiff")
        assertThat(converted.file.audio.container).isEqualTo(AudioContainer.AIFF)
        assertThat(converted.file.audio.bitsPerSample).isEqualTo(16)
        assertThat(converted.file.audio.sampleRateHz).isEqualTo(44_100)
        val file = File(URI(converted.file.uri))
        assertThat(file.parentFile).isEqualTo(folder)
        assertThat(converted.file.sizeBytes).isEqualTo(file.length())
        assertThat(engine.requests.single().sourceUri).isEqualTo(flac.uri)
        assertThat(engine.requests.single().profile).isEqualTo(ConversionProfiles.XDJ_700)
        assertThat(progress).containsExactly(1f)

        converted.close()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a conversion that fails or is cancelled leaves no file and its failure goes on`() = runTest {
        engine.failure = PcmDecodeException.UnsupportedFormat("not audio")
        val decode = runCatching { converter.convert(flac, DeviceProfile.XDJ_700) {} }.exceptionOrNull()
        assertThat(decode).isInstanceOf(PcmDecodeException::class.java)

        engine.failure = CancellationException("left the app")
        val cancelled = runCatching { converter.convert(flac, DeviceProfile.XDJ_700) {} }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)

        assertThat(folder.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `an output the player would not take is refused and deleted`() = runTest {
        engine.sampleRate = byteArrayOf(0x40, 0x0F, 0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0) // 96000

        val refused = runCatching { converter.convert(flac, DeviceProfile.XDJ_700) {} }.exceptionOrNull()

        assertThat(refused).isInstanceOf(IOException::class.java)
        assertThat(refused!!.message).contains("song.flac")
        assertThat(folder.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `leftovers of an earlier export are discarded, and a missing folder is fine`() {
        converter.discardLeftovers()
        folder.mkdirs()
        File(folder, "export-1.aiff").writeBytes(byteArrayOf(1))

        converter.discardLeftovers()

        assertThat(folder.listFiles().orEmpty().toList()).isEmpty()
    }

    /** Writes a short 16-bit stereo AIFF at [sampleRate] (80-bit extended) to the request's file, or throws [failure]. */
    private class FakeEngine : AudioConverter {
        val requests = mutableListOf<ConversionRequest>()
        var failure: Exception? = null
        var sampleRate = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0) // 44100

        override suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit): ConversionResult {
            requests += request
            val output = File(URI(request.outputUri))
            output.writeBytes(byteArrayOf(9, 9, 9)) // a partial output, as a failing engine leaves
            failure?.let { throw it }
            output.writeBytes(aiff(frames = 4))
            onProgress(1f)
            return ConversionResult(ConversionPlan(request.profile, 96_000, 44_100, 2, 2, 16, true), 4, 8)
        }

        override suspend fun verify(outputUri: String, expected: ConversionResult) = OutputVerification.Valid

        private fun aiff(frames: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                val sound = frames * 4
                out.writeBytes("FORM")
                out.writeInt(4 + 8 + 18 + 8 + 8 + sound)
                out.writeBytes("AIFF")
                out.writeBytes("COMM")
                out.writeInt(18)
                out.writeShort(2)
                out.writeInt(frames)
                out.writeShort(16)
                out.write(sampleRate)
                out.writeBytes("SSND")
                out.writeInt(8 + sound)
                out.writeInt(0)
                out.writeInt(0)
                out.write(ByteArray(sound))
            }
            return bytes.toByteArray()
        }
    }
}
