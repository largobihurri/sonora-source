package io.sonora.core.domain.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffBytes
import io.sonora.core.domain.audio.AiffBytes.Companion.extended
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceAudioProbeTest {

    private fun probe(bytes: ByteArray, name: String = "track.aiff", mime: String? = null) =
        SourceAudioProbe.probe(name, mime) { ByteArrayInputStream(bytes) }

    @Test
    fun `a plain AIFF reports its sample size, rate, channels and frames`() {
        val bytes = AiffBytes().comm(channels = 2, frames = 4, bits = 16, rate = extended(48_000)).ssnd(ByteArray(16)).build()

        assertThat(probe(bytes)).isEqualTo(
            SourceAudio(AudioContainer.AIFF, bitsPerSample = 16, sampleRateHz = 48_000, channelCount = 2, frameCount = 4),
        )
    }

    @Test
    fun `chunks before COMM are skipped`() {
        val bytes = AiffBytes().chunk("ID3 ", ByteArray(5000)).comm(bits = 24).ssnd(ByteArray(24)).build()

        assertThat(probe(bytes).bitsPerSample).isEqualTo(24)
    }

    @Test
    fun `AIFF-C is told apart from AIFF, with its compression type`() {
        val sowt = AiffBytes("AIFC").comm(compression = "sowt", compressionName = "").ssnd(ByteArray(16)).build()
        val ima = AiffBytes("AIFC").comm(bits = 16, compression = "ima4", compressionName = "IMA").ssnd(ByteArray(16)).build()

        assertThat(probe(sowt).container).isEqualTo(AudioContainer.AIFF_C)
        assertThat(probe(sowt).compression).isEqualTo("sowt")
        assertThat(probe(ima)).isEqualTo(
            SourceAudio(AudioContainer.AIFF_C, compression = "ima4"),
        )
    }

    @Test
    fun `an AIFF the parser refuses still reports what it found`() {
        val fast = AiffBytes().comm(rate = extended(1_000_000)).ssnd(ByteArray(16)).build()

        assertThat(probe(fast)).isEqualTo(SourceAudio(AudioContainer.AIFF, sampleRateHz = 1_000_000))
    }

    @Test
    fun `other formats are named from their first bytes, whatever the file is called`() {
        assertThat(probe("fLaC".toByteArray() + ByteArray(40), name = "x.aiff").container).isEqualTo(AudioContainer.FLAC)
        assertThat(probe("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()).container).isEqualTo(AudioContainer.WAV)
        assertThat(probe("ID3".toByteArray() + ByteArray(40)).container).isEqualTo(AudioContainer.MP3)
        assertThat(probe("OggS".toByteArray() + ByteArray(40)).container).isEqualTo(AudioContainer.OGG)
    }

    @Test
    fun `without a known signature the name and MIME type decide`() {
        val unknown = ByteArray(64)

        assertThat(probe(unknown, name = "a.m4a").container).isEqualTo(AudioContainer.M4A)
        assertThat(probe(unknown, name = "a.mp3").container).isEqualTo(AudioContainer.MP3)
        assertThat(probe(unknown, name = "a", mime = "audio/flac").container).isEqualTo(AudioContainer.FLAC)
        assertThat(probe(unknown, name = "a.bin").container).isEqualTo(AudioContainer.UNKNOWN)
        assertThat(probe(ByteArray(0), name = "empty.aiff").container).isEqualTo(AudioContainer.UNKNOWN)
    }

    @Test
    fun `a damaged AIFF is an IOException`() {
        val truncated = AiffBytes().comm().ssnd(ByteArray(16)).build().copyOf(30)

        assertThrows(IOException::class.java) { probe(truncated) }
    }

    @Test
    fun `an ADTS AAC stream is not taken for MP3`() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()) + ByteArray(40)
        val mpeg2Adts = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x50, 0x80.toByte()) + ByteArray(40)
        val mp3Layer3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(40)
        val mp2Layer2 = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x90.toByte(), 0x64) + ByteArray(40)

        assertThat(probe(adts, name = "x.mp3").container).isEqualTo(AudioContainer.AAC)
        assertThat(probe(mpeg2Adts, name = "x.mp3").container).isEqualTo(AudioContainer.AAC)
        assertThat(probe(mp3Layer3, name = "x.aac").container).isEqualTo(AudioContainer.MP3)
        assertThat(probe(mp2Layer2, name = "x.aac").container).isEqualTo(AudioContainer.MP3)
    }
}
