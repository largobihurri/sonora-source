package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.security.MessageDigest
import org.junit.Test

class WavPayloadHashTest {

    private val sound = filler(5000, seed = 60)

    private fun idOf(bytes: ByteArray): AudioId = AudioPayloadHasher().identify(ArrayByteSource(bytes))

    private fun wav(vararg before: ByteArray, after: ByteArray = ByteArray(0), sound: ByteArray = this.sound): ByteArray =
        WavBytes.riff(WavBytes.fmt(), *before, WavBytes.data(sound), after)

    @Test
    fun `the id is the sha256 of the tag wav then the fmt body then the data body`() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ascii("wav"))
        digest.update(filler(16, 40))
        digest.update(sound)

        val id = idOf(wav())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `adding editing and removing LIST and other chunks keeps the id`() {
        val bare = idOf(wav())

        assertThat(idOf(wav(WavBytes.chunk("LIST", filler(200, 1))))).isEqualTo(bare)
        assertThat(idOf(wav(WavBytes.chunk("LIST", filler(400, 2)), WavBytes.chunk("bext", filler(602, 3))))).isEqualTo(bare)
        assertThat(idOf(wav(WavBytes.chunk("JUNK", ByteArray(28))))).isEqualTo(bare)
        assertThat(idOf(wav(after = WavBytes.chunk("LIST", filler(100, 4))))).isEqualTo(bare)
        assertThat(idOf(wav(after = WavBytes.chunk("id3 ", filler(1001, 5))))).isEqualTo(bare)
    }

    @Test
    fun `odd sized chunks and their pad byte are handled`() {
        val bare = idOf(wav())

        assertThat(idOf(wav(WavBytes.chunk("LIST", filler(33, 1))))).isEqualTo(bare)
        assertThat(idOf(wav(WavBytes.chunk("LIST", filler(1, 2)), WavBytes.chunk("cue ", filler(7, 3))))).isEqualTo(bare)
    }

    @Test
    fun `an odd sized data chunk keeps its body and drops the pad byte`() {
        val oddSound = filler(4001, 61)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ascii("wav"))
        digest.update(filler(16, 40))
        digest.update(oddSound)

        val id = idOf(wav(after = WavBytes.chunk("LIST", filler(20, 1)), sound = oddSound))

        assertThat(id.hash).isEqualTo(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `flipping one sample byte or one fmt byte changes the id`() {
        val file = wav()

        assertThat(idOf(file.flipped(file.size - 1))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(file.size - 2500))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(12 + 8 + 3))).isNotEqualTo(idOf(file))
    }

    @Test
    fun `an RF64 file with the sizes in ds64 has the same id as the RIFF one`() {
        val rf64 = WavBytes.rf64(
            sound.size.toLong(),
            WavBytes.fmt(),
            WavBytes.chunk("LIST", filler(50, 1)),
            WavBytes.data(sound, declaredSize = 0xFFFFFFFFL),
            WavBytes.chunk("bext", filler(30, 2)),
        )

        val id = idOf(rf64)

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id).isEqualTo(idOf(wav()))
    }

    @Test
    fun `an RF64 data chunk with an odd size is padded by its real size`() {
        val oddSound = filler(3333, 62)
        val rf64 = WavBytes.rf64(
            oddSound.size.toLong(),
            WavBytes.fmt(),
            WavBytes.data(oddSound, declaredSize = 0xFFFFFFFFL),
            WavBytes.chunk("LIST", filler(20, 3)),
        )

        assertThat(idOf(rf64)).isEqualTo(idOf(wav(sound = oddSound)))
    }

    @Test
    fun `a data chunk of size 0xFFFFFFFF without ds64 runs to the end of the file`() {
        val streamed = WavBytes.riff(WavBytes.fmt(), WavBytes.data(sound, declaredSize = 0xFFFFFFFFL))

        assertThat(idOf(streamed)).isEqualTo(idOf(wav()))
    }

    @Test
    fun `a data chunk longer than the file falls back to the whole file`() {
        val truncated = wav().copyOf(wav().size - 100)

        assertThat(idOf(truncated).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a file without a data chunk or without a fmt chunk falls back to the whole file`() {
        assertThat(idOf(WavBytes.riff(WavBytes.fmt(), WavBytes.chunk("LIST", filler(40, 1)))).scheme).isEqualTo("wf1")
        assertThat(idOf(WavBytes.riff(WavBytes.data(sound))).scheme).isEqualTo("wf1")
        assertThat(idOf(WavBytes.riff(WavBytes.fmt(), WavBytes.data(ByteArray(0)))).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a data chunk before the fmt chunk is found in file order`() {
        val dataFirst = WavBytes.riff(WavBytes.data(sound), WavBytes.fmt())

        assertThat(idOf(dataFirst).scheme).isEqualTo("ap1")
        assertThat(idOf(dataFirst)).isNotEqualTo(idOf(wav()))
    }

    @Test
    fun `a riff file that is not wave falls back to the whole file`() {
        val avi = joinBytes(ascii("RIFF"), le32(1000), ascii("AVI "), filler(1000, 7))

        assertThat(idOf(avi).scheme).isEqualTo("wf1")
    }
}
