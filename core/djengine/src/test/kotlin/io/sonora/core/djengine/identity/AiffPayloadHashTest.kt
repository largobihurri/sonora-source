package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.security.MessageDigest
import org.junit.Test

class AiffPayloadHashTest {

    private val sound = filler(4000, seed = 70)

    private fun idOf(bytes: ByteArray): AudioId = AudioPayloadHasher().identify(ArrayByteSource(bytes))

    private fun aiff(vararg before: ByteArray, after: ByteArray = ByteArray(0), sound: ByteArray = this.sound): ByteArray =
        AiffBytes.form(AiffBytes.comm(), *before, AiffBytes.ssnd(sound), after)

    @Test
    fun `the id is the sha256 of the tag aiff then the COMM body then the sound bytes`() {
        val commBody = AiffBytes.comm().copyOfRange(8, 8 + 18)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ascii("aiff"))
        digest.update(commBody)
        digest.update(sound)

        val id = idOf(aiff())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `adding editing and removing chunks keeps the id`() {
        val bare = idOf(aiff())

        assertThat(idOf(aiff(AiffBytes.chunk("NAME", ascii("A title"))))).isEqualTo(bare)
        assertThat(idOf(aiff(AiffBytes.chunk("ANNO", ascii("a comment")), AiffBytes.chunk("MARK", filler(30, 1))))).isEqualTo(bare)
        assertThat(idOf(aiff(after = AiffBytes.chunk("ID3 ", TagBytes.id3v2(300))))).isEqualTo(bare)
        assertThat(idOf(aiff(AiffBytes.chunk("APPL", filler(75, 2)), AiffBytes.chunk("NAME", ascii("x"))))).isEqualTo(bare)
    }

    @Test
    fun `odd sized chunks and an odd sized sound chunk are handled`() {
        val oddSound = filler(3999, 71)

        assertThat(idOf(aiff(AiffBytes.chunk("NAME", ascii("odd")), sound = oddSound, after = AiffBytes.chunk("ANNO", ascii("tail")))))
            .isEqualTo(idOf(aiff(sound = oddSound)))
    }

    @Test
    fun `the offset field skips unused bytes and the block size does not matter`() {
        val plain = idOf(aiff())
        val offset = AiffBytes.form(AiffBytes.comm(), AiffBytes.ssnd(sound, leadBytes = filler(12, 1), blockSize = 4))
        val otherOffset = AiffBytes.form(AiffBytes.comm(), AiffBytes.ssnd(sound, leadBytes = filler(40, 2), blockSize = 0))

        assertThat(idOf(offset)).isEqualTo(plain)
        assertThat(idOf(otherOffset)).isEqualTo(plain)
    }

    @Test
    fun `flipping one sound byte or one COMM byte changes the id`() {
        val file = aiff()

        assertThat(idOf(file.flipped(file.size - 1))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(file.size - 2000))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(12 + 8 + 3))).isNotEqualTo(idOf(file))
    }

    @Test
    fun `AIFF-C walks the same way`() {
        val aifc = AiffBytes.form(AiffBytes.commC(), AiffBytes.chunk("FVER", be32(0xA2805140L)), AiffBytes.ssnd(sound), type = "AIFC")
        val tagged = AiffBytes.form(
            AiffBytes.commC(),
            AiffBytes.chunk("FVER", be32(0xA2805140L)),
            AiffBytes.chunk("NAME", ascii("t")),
            AiffBytes.ssnd(sound),
            AiffBytes.chunk("ID3 ", TagBytes.id3v2(100)),
            type = "AIFC",
        )

        assertThat(idOf(aifc).scheme).isEqualTo("ap1")
        assertThat(idOf(tagged)).isEqualTo(idOf(aifc))
    }

    @Test
    fun `an SSND chunk before COMM is found`() {
        val soundFirst = AiffBytes.form(AiffBytes.ssnd(sound), AiffBytes.comm())

        assertThat(idOf(soundFirst).scheme).isEqualTo("ap1")
    }

    @Test
    fun `a sound chunk longer than the file falls back to the whole file`() {
        val file = aiff()

        assertThat(idOf(file.copyOf(file.size - 50)).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a missing COMM or SSND chunk or an offset past the chunk falls back to the whole file`() {
        assertThat(idOf(AiffBytes.form(AiffBytes.comm(), AiffBytes.chunk("NAME", ascii("x")))).scheme).isEqualTo("wf1")
        assertThat(idOf(AiffBytes.form(AiffBytes.ssnd(sound))).scheme).isEqualTo("wf1")
        val badOffset = AiffBytes.form(AiffBytes.comm(), AiffBytes.chunk("SSND", joinBytes(be32(99999), be32(0), sound)))
        assertThat(idOf(badOffset).scheme).isEqualTo("wf1")
        val tinySsnd = AiffBytes.form(AiffBytes.comm(), AiffBytes.chunk("SSND", ByteArray(4)))
        assertThat(idOf(tinySsnd).scheme).isEqualTo("wf1")
    }
}
