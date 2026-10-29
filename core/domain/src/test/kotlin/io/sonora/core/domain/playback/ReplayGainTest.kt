package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReplayGainTest {

    private val tagged = ReplayGainTags(trackGainDb = -6f, trackPeak = 0.5f, albumGainDb = -8f, albumPeak = 0.9f)

    // dB <-> linear.

    @Test
    fun `decibels convert to linear amplitude and back`() {
        assertThat(Decibels.toLinear(0.0)).isEqualTo(1.0)
        assertThat(Decibels.toLinear(-6.0)).isWithin(1e-9).of(0.501187233627)
        assertThat(Decibels.toLinear(20.0)).isWithin(1e-9).of(10.0)
        assertThat(Decibels.fromLinear(0.5)).isWithin(1e-9).of(-6.020599913280)
        assertThat(Decibels.fromLinear(Decibels.toLinear(3.7))).isWithin(1e-9).of(3.7)
    }

    // Tag values.

    @Test
    fun `gains parse with or without the unit, sign or spaces`() {
        assertThat(ReplayGainTags.parseGain("-6.20 dB")).isWithin(1e-6f).of(-6.2f)
        assertThat(ReplayGainTags.parseGain("+3.10 dB")).isWithin(1e-6f).of(3.1f)
        assertThat(ReplayGainTags.parseGain("  -0.5dB ")).isWithin(1e-6f).of(-0.5f)
        assertThat(ReplayGainTags.parseGain("2.4 db")).isWithin(1e-6f).of(2.4f)
        assertThat(ReplayGainTags.parseGain("-7")).isWithin(1e-6f).of(-7f)
    }

    @Test
    fun `a gain written with a decimal comma still parses`() {
        assertThat(ReplayGainTags.parseGain("-6,20 dB")).isWithin(1e-6f).of(-6.2f)
    }

    @Test
    fun `missing or garbage gains read as absent`() {
        assertThat(ReplayGainTags.parseGain("")).isNull()
        assertThat(ReplayGainTags.parseGain("dB")).isNull()
        assertThat(ReplayGainTags.parseGain("loud")).isNull()
        assertThat(ReplayGainTags.parseGain("NaN dB")).isNull()
        assertThat(ReplayGainTags.parseGain("Infinity")).isNull()
        assertThat(ReplayGainTags.parseGain("-6.2 dB extra")).isNull()
        assertThat(ReplayGainTags.parseGain("-600 dB")).isNull()
    }

    @Test
    fun `peaks parse as positive finite amplitudes`() {
        assertThat(ReplayGainTags.parsePeak("0.988312")).isWithin(1e-6f).of(0.988312f)
        assertThat(ReplayGainTags.parsePeak(" 1.2 ")).isWithin(1e-6f).of(1.2f)
        assertThat(ReplayGainTags.parsePeak("0")).isNull()
        assertThat(ReplayGainTags.parsePeak("-0.5")).isNull()
        assertThat(ReplayGainTags.parsePeak("peak")).isNull()
        assertThat(ReplayGainTags.parsePeak("")).isNull()
    }

    @Test
    fun `tag fields are read by key in any case, ignoring others and garbage`() {
        val tags = ReplayGainTags.fromFields(
            listOf(
                "replaygain_track_gain" to "-6.20 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.977",
                "ReplayGain_Album_Gain" to "nonsense",
                "REPLAYGAIN_ALBUM_PEAK" to "0.999",
                "ARTIST" to "Someone",
            ),
        )

        assertThat(tags.trackGainDb).isWithin(1e-6f).of(-6.2f)
        assertThat(tags.trackPeak).isWithin(1e-6f).of(0.977f)
        assertThat(tags.albumGainDb).isNull()
        assertThat(tags.albumPeak).isWithin(1e-6f).of(0.999f)
    }

    @Test
    fun `the first valid value of a repeated field wins`() {
        val tags = ReplayGainTags.fromFields(
            listOf("REPLAYGAIN_TRACK_GAIN" to "bad", "REPLAYGAIN_TRACK_GAIN" to "-1 dB", "REPLAYGAIN_TRACK_GAIN" to "-2 dB"),
        )

        assertThat(tags.trackGainDb).isWithin(1e-6f).of(-1f)
    }

    @Test
    fun `no fields mean no tags`() {
        assertThat(ReplayGainTags.fromFields(emptyList())).isEqualTo(ReplayGainTags.NONE)
        assertThat(ReplayGainTags.NONE.isEmpty).isTrue()
        assertThat(tagged.isEmpty).isFalse()
    }

    // Mode resolution.

    @Test
    fun `auto uses the album gain only while an album plays in order`() {
        assertThat(ReplayGain.resolveMode(ReplayGainMode.AUTO, albumInOrder = true)).isEqualTo(ReplayGainMode.ALBUM)
        assertThat(ReplayGain.resolveMode(ReplayGainMode.AUTO, albumInOrder = false)).isEqualTo(ReplayGainMode.TRACK)
    }

    @Test
    fun `fixed modes ignore the queue`() {
        for (inOrder in listOf(true, false)) {
            assertThat(ReplayGain.resolveMode(ReplayGainMode.OFF, inOrder)).isEqualTo(ReplayGainMode.OFF)
            assertThat(ReplayGain.resolveMode(ReplayGainMode.TRACK, inOrder)).isEqualTo(ReplayGainMode.TRACK)
            assertThat(ReplayGain.resolveMode(ReplayGainMode.ALBUM, inOrder)).isEqualTo(ReplayGainMode.ALBUM)
        }
    }

    // The gain.

    @Test
    fun `off leaves the signal unchanged`() {
        assertThat(ReplayGain.gain(tagged, ReplayGainSettings(ReplayGainMode.OFF, preampDb = 6f), albumInOrder = true)).isEqualTo(1.0)
    }

    @Test
    fun `track and album modes apply their own gain`() {
        val track = ReplayGain.gain(tagged, ReplayGainSettings(ReplayGainMode.TRACK), albumInOrder = true)
        val album = ReplayGain.gain(tagged, ReplayGainSettings(ReplayGainMode.ALBUM), albumInOrder = false)

        assertThat(track).isWithin(1e-6).of(Decibels.toLinear(-6.0))
        assertThat(album).isWithin(1e-6).of(Decibels.toLinear(-8.0))
    }

    @Test
    fun `auto follows the queue`() {
        val settings = ReplayGainSettings(ReplayGainMode.AUTO)

        assertThat(ReplayGain.gain(tagged, settings, albumInOrder = true)).isWithin(1e-6).of(Decibels.toLinear(-8.0))
        assertThat(ReplayGain.gain(tagged, settings, albumInOrder = false)).isWithin(1e-6).of(Decibels.toLinear(-6.0))
    }

    @Test
    fun `the pre-amp adds to the tagged gain`() {
        val gain = ReplayGain.gain(tagged, ReplayGainSettings(ReplayGainMode.TRACK, preampDb = 3f), albumInOrder = false)

        assertThat(gain).isWithin(1e-6).of(Decibels.toLinear(-3.0))
    }

    @Test
    fun `a missing gain falls back to the other kind`() {
        val trackOnly = ReplayGainTags(trackGainDb = -5f)
        val albumOnly = ReplayGainTags(albumGainDb = -7f)

        assertThat(ReplayGain.gain(trackOnly, ReplayGainSettings(ReplayGainMode.ALBUM), albumInOrder = true))
            .isWithin(1e-6).of(Decibels.toLinear(-5.0))
        assertThat(ReplayGain.gain(albumOnly, ReplayGainSettings(ReplayGainMode.TRACK), albumInOrder = false))
            .isWithin(1e-6).of(Decibels.toLinear(-7.0))
    }

    @Test
    fun `untagged files play unchanged whatever the pre-amp`() {
        for (mode in listOf(ReplayGainMode.TRACK, ReplayGainMode.ALBUM, ReplayGainMode.AUTO)) {
            assertThat(ReplayGain.gain(ReplayGainTags.NONE, ReplayGainSettings(mode, preampDb = 6f), albumInOrder = true)).isEqualTo(1.0)
        }
    }

    @Test
    fun `the peak caps a boost so the loudest sample stays under full scale`() {
        val quiet = ReplayGainTags(trackGainDb = 9f, trackPeak = 0.5f)

        val gain = ReplayGain.gain(quiet, ReplayGainSettings(ReplayGainMode.TRACK), albumInOrder = false)

        assertThat(gain).isWithin(1e-9).of(2.0)
        assertThat(0.5 * gain).isAtMost(1.0)
    }

    @Test
    fun `the cap counts the pre-amp too`() {
        val tags = ReplayGainTags(trackGainDb = -2f, trackPeak = 0.8f)

        val gain = ReplayGain.gain(tags, ReplayGainSettings(ReplayGainMode.TRACK, preampDb = 6f), albumInOrder = false)

        assertThat(gain).isWithin(1e-6).of(1.25)
    }

    @Test
    fun `a gain already under the cap is kept`() {
        val gain = ReplayGain.gain(tagged, ReplayGainSettings(ReplayGainMode.TRACK), albumInOrder = false)

        assertThat(gain * 0.5).isLessThan(1.0)
        assertThat(gain).isWithin(1e-6).of(Decibels.toLinear(-6.0))
    }

    @Test
    fun `without a peak tag a boost is capped at unity, as if the file peaked at full scale`() {
        val noPeak = ReplayGainTags(trackGainDb = 4f, albumGainDb = -3f)

        assertThat(ReplayGain.gain(noPeak, ReplayGainSettings(ReplayGainMode.TRACK), albumInOrder = false)).isEqualTo(1.0)
        assertThat(ReplayGain.gain(noPeak, ReplayGainSettings(ReplayGainMode.ALBUM, preampDb = 6f), albumInOrder = true))
            .isEqualTo(1.0)
        // A cut is still a cut.
        assertThat(ReplayGain.gain(noPeak, ReplayGainSettings(ReplayGainMode.ALBUM), albumInOrder = true))
            .isWithin(1e-6).of(Decibels.toLinear(-3.0))
    }

    @Test
    fun `the album gain is capped by the album peak, not the track peak`() {
        val tags = ReplayGainTags(trackGainDb = 0f, trackPeak = 0.25f, albumGainDb = 6f, albumPeak = 0.8f)

        val gain = ReplayGain.gain(tags, ReplayGainSettings(ReplayGainMode.ALBUM), albumInOrder = true)

        assertThat(gain).isWithin(1e-6).of(1.25)
    }

    @Test
    fun `the pre-amp is kept within its range`() {
        assertThat(ReplayGainSettings(preampDb = 9f).preampDb).isEqualTo(ReplayGainSettings.MAX_PREAMP_DB)
        assertThat(ReplayGainSettings(preampDb = -9f).preampDb).isEqualTo(ReplayGainSettings.MIN_PREAMP_DB)
        assertThat(ReplayGainSettings(preampDb = Float.NaN).preampDb).isEqualTo(0f)
        assertThat(ReplayGainSettings().mode).isEqualTo(ReplayGainMode.OFF)
    }

    // Albums in order.

    private fun at(track: Int?, disc: Int? = 1, album: String = "a") = AlbumPosition(album, disc, track)

    @Test
    fun `a track next to its album neighbour plays an album in order`() {
        assertThat(ReplayGain.albumInOrder(previous = at(3), current = at(4), next = null)).isTrue()
        assertThat(ReplayGain.albumInOrder(previous = null, current = at(1), next = at(2))).isTrue()
        assertThat(ReplayGain.albumInOrder(previous = at(9, album = "b"), current = at(4), next = at(5))).isTrue()
    }

    @Test
    fun `a disc change continues the album`() {
        assertThat(ReplayGain.albumInOrder(previous = at(12, disc = 1), current = at(1, disc = 2), next = null)).isTrue()
        assertThat(ReplayGain.albumInOrder(previous = at(12, disc = null), current = at(13, disc = null), next = null)).isTrue()
    }

    @Test
    fun `shuffled, mixed or lone tracks are not an album in order`() {
        assertThat(ReplayGain.albumInOrder(previous = at(7), current = at(2), next = at(9))).isFalse()
        assertThat(ReplayGain.albumInOrder(previous = at(3, album = "b"), current = at(4), next = at(5, album = "c"))).isFalse()
        assertThat(ReplayGain.albumInOrder(previous = null, current = at(4), next = null)).isFalse()
        assertThat(ReplayGain.albumInOrder(previous = at(null), current = at(null), next = null)).isFalse()
        assertThat(ReplayGain.albumInOrder(previous = at(1, album = ""), current = at(2, album = ""), next = null)).isFalse()
    }
}
