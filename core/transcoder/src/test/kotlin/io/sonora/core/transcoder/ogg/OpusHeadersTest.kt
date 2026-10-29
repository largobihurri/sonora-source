package io.sonora.core.transcoder.ogg

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class OpusHeadersTest {

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `OpusHead for stereo from a 44_1 kHz source with the default pre-skip is the 19 bytes of RFC 7845`() {
        val head = OpusHeaders.head(channels = 2, preSkip = 312, inputSampleRateHz = 44_100)

        // "OpusHead", version 1, 2 channels, pre-skip 312, rate 44100, gain 0, mapping family 0.
        assertThat(head.toHex()).isEqualTo("4f70757348656164" + "01" + "02" + "3801" + "44ac0000" + "0000" + "00")
    }

    @Test
    fun `OpusHead for mono carries the channel count the pre-skip and the output gain`() {
        val head = OpusHeaders.head(channels = 1, preSkip = 65_535, inputSampleRateHz = 48_000, outputGainQ78 = -256)

        assertThat(head.toHex()).isEqualTo("4f70757348656164" + "01" + "01" + "ffff" + "80bb0000" + "00ff" + "00")
    }

    @Test
    fun `OpusHead rejects channel counts and pre-skips family 0 cannot carry`() {
        assertThrows(IllegalArgumentException::class.java) { OpusHeaders.head(0, 312, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { OpusHeaders.head(3, 312, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { OpusHeaders.head(2, -1, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { OpusHeaders.head(2, 65_536, 44_100) }
    }

    @Test
    fun `OpusTags holds the vendor and the comments as length-prefixed UTF-8`() {
        val tags = OpusHeaders.tags("Sonora", listOf(VorbisComment("TITLE", "Aria"), VorbisComment("ARTIST", "Bach")))

        assertThat(tags.toHex()).isEqualTo(
            "4f70757354616773" + // OpusTags
                "06000000" + "536f6e6f7261" + // vendor, 6 bytes
                "02000000" + // two comments
                "0a000000" + "5449544c453d41726961" + // TITLE=Aria
                "0b000000" + "4152544953543d42616368", // ARTIST=Bach
        )
    }

    @Test
    fun `OpusTags lengths count bytes and not characters`() {
        val tags = OpusHeaders.tags("Ünï", listOf(VorbisComment("TITLE", "Süße Träume ♪")))

        val vendor = "Ünï".toByteArray(Charsets.UTF_8)
        assertThat(vendor.size).isEqualTo(5)
        assertThat(tags.copyOfRange(8, 12).toHex()).isEqualTo("05000000")
        val title = "TITLE=Süße Träume ♪".toByteArray(Charsets.UTF_8)
        val commentAt = 8 + 4 + vendor.size + 4
        assertThat(tags.copyOfRange(commentAt, commentAt + 4).toHex()).isEqualTo("%02x000000".format(title.size))
        assertThat(String(tags, commentAt + 4, title.size, Charsets.UTF_8)).isEqualTo("TITLE=Süße Träume ♪")
        assertThat(tags.size).isEqualTo(commentAt + 4 + title.size)
    }

    @Test
    fun `OpusTags without comments is the magic and the vendor`() {
        val tags = OpusHeaders.tags("S", emptyList())

        assertThat(tags.toHex()).isEqualTo("4f70757354616773" + "01000000" + "53" + "00000000")
    }

    @Test
    fun `a comment name may not contain an equals sign or characters outside printable ASCII`() {
        assertThrows(IllegalArgumentException::class.java) { VorbisComment("A=B", "x") }
        assertThrows(IllegalArgumentException::class.java) { VorbisComment("", "x") }
        assertThrows(IllegalArgumentException::class.java) { VorbisComment("TÍTULO", "x") }
        VorbisComment("METADATA_BLOCK_PICTURE", "x")
    }

    @Test
    fun `packet durations follow the TOC byte of RFC 6716`() {
        // CELT full band: configuration 28..31 are 2.5, 5, 10 and 20 ms.
        assertThat(OpusPackets.sampleCount(hex("fc00"))).isEqualTo(960) // 20 ms stereo, one frame
        assertThat(OpusPackets.sampleCount(hex("f800"))).isEqualTo(960) // 20 ms mono
        assertThat(OpusPackets.sampleCount(hex("f000"))).isEqualTo(480) // 10 ms
        assertThat(OpusPackets.sampleCount(hex("e000"))).isEqualTo(120) // 2.5 ms
        assertThat(OpusPackets.sampleCount(hex("e800"))).isEqualTo(240) // 5 ms
        // Code 1 and 2: two frames; code 3: the frame count in the next byte.
        assertThat(OpusPackets.sampleCount(hex("f90000"))).isEqualTo(1_920)
        assertThat(OpusPackets.sampleCount(hex("fa0000"))).isEqualTo(1_920)
        assertThat(OpusPackets.sampleCount(hex("fb03"))).isEqualTo(2_880)
        assertThat(OpusPackets.sampleCount(hex("fb83"))).isEqualTo(2_880) // VBR and padding flags ignored
        // The configuration is the top five bits of the TOC byte. SILK wideband (configuration 8..11: 10, 20, 40,
        // 60 ms) is 0x40, 0x48, 0x50 and 0x58; hybrid (12..15: 10, 20 ms twice) starts at 0x60.
        assertThat(OpusPackets.sampleCount(hex("4000"))).isEqualTo(480)
        assertThat(OpusPackets.sampleCount(hex("4800"))).isEqualTo(960)
        assertThat(OpusPackets.sampleCount(hex("5000"))).isEqualTo(1_920)
        assertThat(OpusPackets.sampleCount(hex("5800"))).isEqualTo(2_880)
        assertThat(OpusPackets.sampleCount(hex("6000"))).isEqualTo(480)
        assertThat(OpusPackets.sampleCount(hex("6800"))).isEqualTo(960)
        assertThat(OpusPackets.sampleCount(hex("7000"))).isEqualTo(480)
        assertThat(OpusPackets.sampleCount(hex("7800"))).isEqualTo(960)
        // SILK narrowband 10 ms is configuration 0.
        assertThat(OpusPackets.sampleCount(hex("0000"))).isEqualTo(480)
    }

    @Test
    fun `packets that cannot be Opus are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.sampleCount(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.sampleCount(hex("fb")) } // code 3, no count
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.sampleCount(hex("fb00")) } // zero frames
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.sampleCount(hex("fb30")) } // 48 x 20 ms > 120 ms
    }

    @Test
    fun `the pre-skip comes from the encoder's OpusHead first`() {
        val head = OpusHeaders.head(2, 410, 48_000)

        assertThat(OpusStreamConfig.preSkip(head, codecDelayNs = 6_500_000)).isEqualTo(410)
    }

    @Test
    fun `without an OpusHead the pre-skip comes from the codec delay`() {
        assertThat(OpusStreamConfig.preSkip(null, codecDelayNs = 6_500_000)).isEqualTo(312)
        assertThat(OpusStreamConfig.preSkip(ByteArray(4), codecDelayNs = 8_000_000)).isEqualTo(384)
    }

    @Test
    fun `without any codec data the pre-skip is 312 samples`() {
        assertThat(OpusStreamConfig.preSkip(null, null)).isEqualTo(312)
        assertThat(OpusStreamConfig.preSkip(null, 0)).isEqualTo(312)
        assertThat(OpusStreamConfig.preSkip(hex("4f707573486561"), -5)).isEqualTo(312) // truncated head
    }
}
