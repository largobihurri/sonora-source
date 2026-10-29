package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.model.ConverterPreferences
import org.junit.Assert.assertThrows
import org.junit.Test

class OutputTargetsTest {

    /** Every target a user can pick: each format with each of its settings. */
    private val everyTarget: List<OutputTarget> = buildList {
        add(OutputTarget.Xdj700)
        for (format in listOf(AudioFormat.AIFF, AudioFormat.WAV, AudioFormat.FLAC)) {
            for (bits in OutputTarget.Lossless.BIT_DEPTHS) {
                add(OutputTarget.Lossless(format, bits, sampleRateCapHz = null))
                OutputTarget.Lossless.SAMPLE_RATE_CAPS_HZ.forEach { add(OutputTarget.Lossless(format, bits, it)) }
            }
        }
        OutputTarget.Aac.BITRATES_KBPS.forEach { add(OutputTarget.Aac(it)) }
        OutputTarget.Opus.BITRATES_KBPS.forEach { add(OutputTarget.Opus(it)) }
        OutputTarget.Mp3.CBR_BITRATES_KBPS.forEach { add(OutputTarget.Mp3(Mp3Mode.Cbr(it))) }
        OutputTarget.Mp3.VBR_QUALITIES.forEach { add(OutputTarget.Mp3(Mp3Mode.Vbr(it))) }
    }

    @Test
    fun `every target of every format round-trips through its id, as a target and as a profile`() {
        val formats = everyTarget.map { it.format }.toSet()
        assertThat(formats).containsExactlyElementsIn(AudioFormat.entries)
        for (target in everyTarget) {
            val profile = OutputTargets.profileFor(target)
            assertThat(profile.id).isEqualTo(target.id)
            assertThat(OutputTargets.parse(target.id)).isEqualTo(target)
            assertThat(ConversionProfiles.byId(target.id)).isEqualTo(profile)
        }
        assertThat(everyTarget.map { it.id }.toSet()).hasSize(everyTarget.size)
    }

    @Test
    fun `ids are short, stable and readable`() {
        assertThat(OutputTarget.Lossless(AudioFormat.FLAC, 24, null).id).isEqualTo("out:flac:24:keep")
        assertThat(OutputTarget.Lossless(AudioFormat.WAV, 16, 48_000).id).isEqualTo("out:wav:16:48000")
        assertThat(OutputTarget.Lossless(AudioFormat.AIFF, 24, 96_000).id).isEqualTo("out:aiff:24:96000")
        assertThat(OutputTarget.Aac(256).id).isEqualTo("out:aac:256")
        assertThat(OutputTarget.Opus(160).id).isEqualTo("out:opus:160")
        assertThat(OutputTarget.Mp3(Mp3Mode.Cbr(320)).id).isEqualTo("out:mp3:cbr:320")
        assertThat(OutputTarget.Mp3(Mp3Mode.Vbr(0)).id).isEqualTo("out:mp3:vbr:0")
        assertThat(OutputTarget.Xdj700.id).isEqualTo("xdj-700")
    }

    @Test
    fun `the XDJ-700 stays its own target with its id and rules unchanged`() {
        val profile = OutputTargets.profileFor(OutputTarget.Xdj700)

        assertThat(profile).isSameInstanceAs(ConversionProfiles.XDJ_700)
        assertThat(profile.id).isEqualTo("xdj-700")
        assertThat(profile.container).isEqualTo(OutputContainer.AIFF)
        assertThat(profile.sampleFormat).isEqualTo(OutputSampleFormat.PCM_16_BIG_ENDIAN)
        assertThat(profile.sampleRate).isEqualTo(SampleRateRule(maxSampleRateHz = 48_000, resampleTargetHz = 44_100))
        assertThat(profile.dither).isEqualTo(DitherPolicy.TPDF_WHEN_REDUCING_PRECISION)
        assertThat(OutputTargets.parse("xdj-700")).isEqualTo(OutputTarget.Xdj700)
        assertThat(ConversionProfiles.byId("xdj-700")).isSameInstanceAs(ConversionProfiles.XDJ_700)
        // An AIFF 16-bit target the user sets up is a different choice with a different id.
        assertThat(OutputTarget.Lossless(AudioFormat.AIFF, 16, 48_000).id).isNotEqualTo("xdj-700")
    }

    @Test
    fun `the watch profile is still found by id but is no target to pick`() {
        assertThat(ConversionProfiles.byId("watch-opus")).isSameInstanceAs(ConversionProfiles.WATCH_OPUS)
        assertThat(OutputTargets.parse("watch-opus")).isNull()
        assertThat(everyTarget.map { it.id }).doesNotContain("watch-opus")
    }

    @Test
    fun `unknown or malformed ids find nothing`() {
        val unknown = listOf(
            "", "cdj-3000", "out", "out:", "out:flac", "out:flac:24", "out:flac:20:keep", "out:flac:24:22050",
            "out:flac:24:keep:extra", "out:FLAC:24:keep", "out:flac:24:Keep", "out:aac:999", "out:aac:", "out:aac:256:x",
            "out:opus:64", "out:mp3:cbr:96", "out:mp3:vbr:1", "out:mp3:abr:192", "out:mp3:320", "out:alac:16:keep",
            "out:aiff:16:-1", "xdj-700:out",
        )
        for (id in unknown) {
            assertThat(OutputTargets.parse(id)).isNull()
            assertThat(ConversionProfiles.byId(id)).isNull()
        }
    }

    @Test
    fun `lossless targets keep the bit depth and rate the user chose`() {
        val flac = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, 24, null))
        assertThat(flac.container).isEqualTo(OutputContainer.FLAC)
        assertThat(flac.sampleFormat).isEqualTo(OutputSampleFormat.FLAC_24)
        assertThat(flac.sampleRate.targetFor(192_000)).isEqualTo(192_000)
        assertThat(flac.sampleRate.targetFor(44_100)).isEqualTo(44_100)
        assertThat(flac.dither).isEqualTo(DitherPolicy.TPDF_WHEN_REDUCING_PRECISION)
        assertThat(flac.bitrateBps).isNull()
        assertThat(flac.isLossy).isFalse()
        assertThat(flac.fileExtension).isEqualTo("flac")

        val wav = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV, 16, 48_000))
        assertThat(wav.container).isEqualTo(OutputContainer.WAV)
        assertThat(wav.sampleFormat).isEqualTo(OutputSampleFormat.PCM_16_LITTLE_ENDIAN)
        assertThat(wav.sampleRate.targetFor(96_000)).isEqualTo(48_000)
        assertThat(wav.sampleRate.targetFor(48_000)).isEqualTo(48_000)
        assertThat(wav.sampleRate.targetFor(44_100)).isEqualTo(44_100)
        assertThat(wav.fileExtension).isEqualTo("wav")

        val aiff = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.AIFF, 24, 44_100))
        assertThat(aiff.container).isEqualTo(OutputContainer.AIFF)
        assertThat(aiff.sampleFormat).isEqualTo(OutputSampleFormat.PCM_24_BIG_ENDIAN)
        assertThat(aiff.sampleRate.targetFor(48_000)).isEqualTo(44_100)
        assertThat(OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.AIFF, 16, null)).sampleFormat)
            .isEqualTo(OutputSampleFormat.PCM_16_BIG_ENDIAN)
        assertThat(OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV, 24, null)).sampleFormat)
            .isEqualTo(OutputSampleFormat.PCM_24_LITTLE_ENDIAN)
        assertThat(OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, 16, null)).sampleFormat)
            .isEqualTo(OutputSampleFormat.FLAC_16)
    }

    @Test
    fun `lossy targets carry their bitrate and are lossy`() {
        val aac = OutputTargets.profileFor(OutputTarget.Aac(192))
        assertThat(aac.container).isEqualTo(OutputContainer.M4A)
        assertThat(aac.sampleFormat).isEqualTo(OutputSampleFormat.AAC)
        assertThat(aac.bitrateBps).isEqualTo(192_000)
        assertThat(aac.fileExtension).isEqualTo("m4a")
        assertThat(aac.sampleRate.targetFor(96_000)).isEqualTo(48_000)
        assertThat(aac.sampleRate.targetFor(44_100)).isEqualTo(44_100)
        assertThat(aac.isLossy).isTrue()

        val opus = OutputTargets.profileFor(OutputTarget.Opus(128))
        assertThat(opus.container).isEqualTo(OutputContainer.OGG_OPUS)
        assertThat(opus.sampleFormat).isEqualTo(OutputSampleFormat.OPUS)
        assertThat(opus.bitrateBps).isEqualTo(128_000)
        assertThat(opus.sampleRate.targetFor(44_100)).isEqualTo(48_000)
        assertThat(opus.isLossy).isTrue()

        val cbr = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Cbr(256)))
        assertThat(cbr.container).isEqualTo(OutputContainer.MP3)
        assertThat(cbr.sampleFormat).isEqualTo(OutputSampleFormat.MP3)
        assertThat(cbr.bitrateBps).isEqualTo(256_000)
        assertThat(cbr.vbrQuality).isNull()
        assertThat(cbr.fileExtension).isEqualTo("mp3")
        assertThat(cbr.isLossy).isTrue()

        val vbr = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Vbr(2)))
        assertThat(vbr.bitrateBps).isNull()
        assertThat(vbr.vbrQuality).isEqualTo(2)

        assertThat(ConversionProfiles.XDJ_700.isLossy).isFalse()
        assertThat(ConversionProfiles.WATCH_OPUS.isLossy).isTrue()
    }

    @Test
    fun `each format starts on sensible settings`() {
        assertThat(OutputTargets.defaultFor(AudioFormat.AIFF)).isEqualTo(OutputTarget.Lossless(AudioFormat.AIFF, 16, null))
        assertThat(OutputTargets.defaultFor(AudioFormat.WAV)).isEqualTo(OutputTarget.Lossless(AudioFormat.WAV, 16, null))
        assertThat(OutputTargets.defaultFor(AudioFormat.FLAC)).isEqualTo(OutputTarget.Lossless(AudioFormat.FLAC, 16, null))
        assertThat(OutputTargets.defaultFor(AudioFormat.AAC_M4A)).isEqualTo(OutputTarget.Aac(256))
        assertThat(OutputTargets.defaultFor(AudioFormat.OPUS)).isEqualTo(OutputTarget.Opus(160))
        assertThat(OutputTargets.defaultFor(AudioFormat.MP3)).isEqualTo(OutputTarget.Mp3(Mp3Mode.Cbr(320)))
        assertThat(OutputTarget.Mp3.DEFAULT_VBR).isEqualTo(Mp3Mode.Vbr(0))
        assertThat(OutputTarget.Aac.BITRATES_KBPS).containsExactly(128, 192, 256, 320).inOrder()
        assertThat(OutputTarget.Opus.BITRATES_KBPS).containsExactly(96, 128, 160, 192, 256).inOrder()
        assertThat(OutputTarget.Mp3.CBR_BITRATES_KBPS).containsExactly(128, 192, 256, 320).inOrder()
        assertThat(OutputTarget.Mp3.VBR_QUALITIES).containsExactly(0, 2, 4).inOrder()
        assertThat(OutputTarget.Lossless.BIT_DEPTHS).containsExactly(16, 24).inOrder()
        assertThat(OutputTarget.Lossless.SAMPLE_RATE_CAPS_HZ).containsExactly(44_100, 48_000, 96_000).inOrder()
    }

    @Test
    fun `settings outside the offered choices are refused`() {
        assertThrows(IllegalArgumentException::class.java) { OutputTarget.Lossless(AudioFormat.MP3, 16, null) }
        assertThrows(IllegalArgumentException::class.java) { OutputTarget.Lossless(AudioFormat.FLAC, 20, null) }
        assertThrows(IllegalArgumentException::class.java) { OutputTarget.Lossless(AudioFormat.FLAC, 16, 22_050) }
        assertThrows(IllegalArgumentException::class.java) { OutputTarget.Aac(100) }
        assertThrows(IllegalArgumentException::class.java) { OutputTarget.Opus(320) }
        assertThrows(IllegalArgumentException::class.java) { Mp3Mode.Cbr(96) }
        assertThrows(IllegalArgumentException::class.java) { Mp3Mode.Vbr(1) }
    }

    @Test
    fun `the remembered batch-wide output reads as its target, and as the XDJ-700 when unknown`() {
        assertThat(ConverterPreferences().outputTarget).isEqualTo(OutputTarget.Xdj700)
        assertThat(ConverterPreferences("out:flac:24:keep").outputTarget).isEqualTo(OutputTarget.Lossless(AudioFormat.FLAC, 24, null))
        assertThat(ConverterPreferences("out:mp3:vbr:4").outputTarget).isEqualTo(OutputTarget.Mp3(Mp3Mode.Vbr(4)))
        assertThat(ConverterPreferences("watch-opus").outputTarget).isEqualTo(OutputTarget.Xdj700)
        assertThat(ConverterPreferences("out:aac:999").outputTarget).isEqualTo(OutputTarget.Xdj700)
        assertThat(ConverterPreferences.of(OutputTarget.Opus(96)).outputProfileId).isEqualTo("out:opus:96")
    }
}
