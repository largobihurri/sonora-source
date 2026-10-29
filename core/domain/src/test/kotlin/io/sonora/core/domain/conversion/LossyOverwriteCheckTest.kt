package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AudioFormat
import org.junit.Test

class LossyOverwriteCheckTest {

    private val mp3 = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Cbr(320)))
    private val aac = OutputTargets.profileFor(OutputTarget.Aac(256))
    private val opus = OutputTargets.profileFor(OutputTarget.Opus(160))
    private val flac = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, 16, null))
    private val wav = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV, 24, null))

    private fun item(name: String, target: ConversionProfile, codec: String? = null) =
        LossyOverwriteCheck.Item(sourceName = name, sourceCodec = codec, target = target)

    @Test
    fun `lossless originals that would be replaced by lossy files are counted`() {
        val items = listOf(
            item("a.flac", mp3),
            item("b.wav", aac),
            item("c.aiff", opus),
            item("d.aif", mp3),
            item("e.m4a", aac, codec = "ALAC"),
            item("f.ape", mp3),
            item("g.wv", opus),
        )

        assertThat(LossyOverwriteCheck.count(items, overwriteOriginals = true)).isEqualTo(7)
    }

    @Test
    fun `nothing is counted while originals are kept`() {
        assertThat(LossyOverwriteCheck.count(listOf(item("a.flac", mp3)), overwriteOriginals = false)).isEqualTo(0)
    }

    @Test
    fun `lossless outputs, lossy originals and the XDJ-700 are never counted`() {
        val items = listOf(
            item("a.flac", flac),
            item("b.flac", wav),
            item("c.wav", ConversionProfiles.XDJ_700),
            item("d.mp3", opus),
            item("e.ogg", mp3),
            item("f.m4a", mp3, codec = "AAC"),
            item("g.opus", aac),
        )

        assertThat(LossyOverwriteCheck.count(items, overwriteOriginals = true)).isEqualTo(0)
    }

    @Test
    fun `an M4A of unknown codec is not counted, since it is most often AAC`() {
        assertThat(LossyOverwriteCheck.count(listOf(item("a.m4a", mp3)), overwriteOriginals = true)).isEqualTo(0)
    }

    @Test
    fun `the codec the library read wins over the file name, and names match whatever their case`() {
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.FLAC", codec = null)).isTrue()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.Wav", codec = null)).isTrue()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.aifc", codec = null)).isTrue()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.flac", codec = "mp3")).isFalse()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.mp3", codec = "flac")).isTrue()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track", codec = null)).isFalse()
        assertThat(LossyOverwriteCheck.isLosslessSource("Track.", codec = null)).isFalse()
        assertThat(LossyOverwriteCheck.isLosslessSource(".flac", codec = null)).isTrue()
        assertThat(LossyOverwriteCheck.isLosslessSource("a.flac.mp3", codec = null)).isFalse()
        assertThat(LossyOverwriteCheck.isLosslessSource("a.m4a", codec = "alac")).isTrue()
    }

    @Test
    fun `an empty batch counts nothing`() {
        assertThat(LossyOverwriteCheck.count(emptyList(), overwriteOriginals = true)).isEqualTo(0)
    }
}
