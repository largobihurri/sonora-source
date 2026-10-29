package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.security.MessageDigest
import org.junit.Test

class Mp3PayloadHashTest {

    private val audio = Mp3Bytes.stream(12)

    private fun idOf(bytes: ByteArray): AudioId = AudioPayloadHasher().identify(ArrayByteSource(bytes))

    private fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `the id is the sha256 of the tag mp3 followed by the frames`() {
        val id = idOf(joinBytes(TagBytes.id3v2(300), audio, TagBytes.id3v1()))

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(sha256Hex(ascii("mp3"), audio))
    }

    @Test
    fun `a bare stream of frames has the same id as a tagged one`() {
        assertThat(idOf(audio)).isEqualTo(idOf(joinBytes(TagBytes.id3v2(1000), audio, TagBytes.id3v1())))
    }

    @Test
    fun `adding editing and removing an ID3v2 tag keeps the id`() {
        val bare = idOf(audio)

        assertThat(idOf(joinBytes(TagBytes.id3v2(100, seed = 1), audio))).isEqualTo(bare)
        assertThat(idOf(joinBytes(TagBytes.id3v2(5000, seed = 99), audio))).isEqualTo(bare)
        assertThat(idOf(joinBytes(TagBytes.id3v2(0), audio))).isEqualTo(bare)
    }

    @Test
    fun `every leading ID3v2 tag is skipped including one with a footer`() {
        val tagged = joinBytes(TagBytes.id3v2(64), TagBytes.id3v2(200, seed = 7, footer = true), TagBytes.id3v2(10), audio)

        assertThat(idOf(tagged)).isEqualTo(idOf(audio))
    }

    @Test
    fun `an ID3v1 tag at the end is ignored`() {
        assertThat(idOf(joinBytes(audio, TagBytes.id3v1(2)))).isEqualTo(idOf(audio))
        assertThat(idOf(joinBytes(audio, TagBytes.id3v1(77)))).isEqualTo(idOf(audio))
    }

    @Test
    fun `an enhanced TAG plus block and its ID3v1 tag are ignored`() {
        assertThat(idOf(joinBytes(audio, TagBytes.tagPlus(), TagBytes.id3v1()))).isEqualTo(idOf(audio))
    }

    @Test
    fun `an APEv2 tag with or without a header is ignored`() {
        val bare = idOf(audio)

        assertThat(idOf(joinBytes(audio, TagBytes.ape(120)))).isEqualTo(bare)
        assertThat(idOf(joinBytes(audio, TagBytes.ape(120, header = true)))).isEqualTo(bare)
        assertThat(idOf(joinBytes(audio, TagBytes.ape(40, seed = 9, header = true), TagBytes.id3v1()))).isEqualTo(bare)
    }

    @Test
    fun `a Lyrics3v2 block is ignored`() {
        val bare = idOf(audio)

        assertThat(idOf(joinBytes(audio, TagBytes.lyrics3(200)))).isEqualTo(bare)
        assertThat(idOf(joinBytes(audio, TagBytes.lyrics3(10), TagBytes.id3v1()))).isEqualTo(bare)
    }

    @Test
    fun `an appended ID3v2 tag with a footer is ignored`() {
        assertThat(idOf(joinBytes(audio, TagBytes.id3v2(300, footer = true)))).isEqualTo(idOf(audio))
    }

    @Test
    fun `trailing tags are stripped whatever their order`() {
        val bare = idOf(audio)

        val stacked = joinBytes(
            TagBytes.id3v2(80),
            audio,
            TagBytes.id3v2(50, seed = 3, footer = true),
            TagBytes.lyrics3(30),
            TagBytes.ape(60, header = true),
            TagBytes.tagPlus(),
            TagBytes.id3v1(),
        )
        val reordered = joinBytes(audio, TagBytes.ape(60), TagBytes.lyrics3(30), TagBytes.id3v1())

        assertThat(idOf(stacked)).isEqualTo(bare)
        assertThat(idOf(reordered)).isEqualTo(bare)
    }

    @Test
    fun `flipping one audio byte changes the id`() {
        val tagged = joinBytes(TagBytes.id3v2(200), audio, TagBytes.id3v1())
        val before = idOf(tagged)

        assertThat(idOf(tagged.flipped(210 + 5))).isNotEqualTo(before)
        assertThat(idOf(tagged.flipped(210 + audio.size / 2))).isNotEqualTo(before)
        assertThat(idOf(tagged.flipped(210 + audio.size - 1))).isNotEqualTo(before)
    }

    @Test
    fun `the Xing frame is part of the payload`() {
        val xing = Mp3Bytes.frame(Mp3Bytes.MPEG1_L3, seed = 500, marker = "Xing")
        val stream = joinBytes(xing, Mp3Bytes.stream(8))
        val tagged = joinBytes(TagBytes.id3v2(90), stream, TagBytes.id3v1())

        assertThat(idOf(tagged)).isEqualTo(idOf(stream))
        assertThat(idOf(tagged.flipped(100 + 40))).isNotEqualTo(idOf(tagged))
        assertThat(idOf(stream)).isNotEqualTo(idOf(Mp3Bytes.stream(8)))
    }

    @Test
    fun `junk between the ID3 tag and the first frame is tolerated`() {
        val junk = filler(333, seed = 8)
        val withJunk = joinBytes(TagBytes.id3v2(64), junk, audio)

        assertThat(idOf(withJunk).scheme).isEqualTo("ap1")
        assertThat(idOf(withJunk)).isEqualTo(idOf(joinBytes(TagBytes.id3v2(900), junk, audio)))
        assertThat(idOf(withJunk)).isEqualTo(idOf(audio))
    }

    @Test
    fun `an unconfirmed sync pattern in the junk is not taken for the first frame`() {
        val falseHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + filler(500, 12)
        val withJunk = joinBytes(TagBytes.id3v2(64), falseHeader, audio)

        assertThat(idOf(withJunk).scheme).isEqualTo("ap1")
        assertThat(idOf(withJunk)).isEqualTo(idOf(audio))
    }

    @Test
    fun `every MPEG version layer and padding walks`() {
        val specs = listOf(
            Mp3Bytes.MPEG1_L1,
            Mp3Bytes.MPEG1_L2,
            Mp3Bytes.MPEG1_L3,
            Mp3Bytes.MPEG2_L3,
            Mp3Bytes.MPEG25_L3,
        )
        for (spec in specs) {
            val stream = Mp3Bytes.streamOf(spec, 6)
            val tagged = joinBytes(TagBytes.id3v2(40), stream, TagBytes.id3v1())

            assertThat(idOf(tagged).scheme).isEqualTo("ap1")
            assertThat(idOf(tagged)).isEqualTo(idOf(stream))
        }
        val tagged = joinBytes(TagBytes.id3v2(40), audio)
        assertThat(idOf(tagged).scheme).isEqualTo("ap1")
    }

    @Test
    fun `an id3 tag with no frames after it falls back to the whole file`() {
        val id = idOf(TagBytes.id3v2(300))

        assertThat(id.scheme).isEqualTo("wf1")
    }

    @Test
    fun `a single frame cannot be confirmed so the whole file is hashed`() {
        val id = idOf(joinBytes(TagBytes.id3v2(50), Mp3Bytes.frame(Mp3Bytes.MPEG1_L3, 1)))

        assertThat(id.scheme).isEqualTo("wf1")
    }

    @Test
    fun `a truncated ID3v2 tag falls back to the whole file`() {
        val tag = TagBytes.id3v2(500)

        assertThat(idOf(tag.copyOf(100)).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a frame whose successor header is wrong is not accepted`() {
        val first = Mp3Bytes.frame(Mp3Bytes.MPEG1_L3, 1)
        val broken = joinBytes(first, filler(417, 2), filler(417, 3))

        assertThat(idOf(broken).scheme).isEqualTo("wf1")
    }
}
