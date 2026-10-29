package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4PayloadHashTest {

    private val sound = filler(5000, seed = 80)

    private fun idOf(bytes: ByteArray, maxRead: Int = Int.MAX_VALUE): AudioId =
        AudioPayloadHasher().identify(ArrayByteSource(bytes, maxRead))

    private fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** ftyp, moov, mdat: the layout of a file written without fast start. */
    private fun m4a(sound: ByteArray = this.sound, tagBytes: Int = 200): ByteArray =
        joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(sound), Mp4Bytes.moov(tagBytes, chunkOffset = 40))

    @Test
    fun `the id is the sha256 of the tag mp4 then the mdat payload`() {
        val id = idOf(m4a())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(sha256Hex(ascii("mp4"), sound))
    }

    @Test
    fun `growing and shrinking the tags keeps the id`() {
        val reference = idOf(m4a(tagBytes = 200))

        for (tagBytes in intArrayOf(0, 1, 50, 5_000, 200_000)) {
            assertThat(idOf(m4a(tagBytes = tagBytes))).isEqualTo(reference)
        }
    }

    @Test
    fun `moving moov before or after mdat keeps the id`() {
        val moovLast = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(sound), Mp4Bytes.moov(300, chunkOffset = 40))
        val fastStart = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(9_000, chunkOffset = 9_500), Mp4Bytes.mdat(sound))
        val padded = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(10, chunkOffset = 1234), Mp4Bytes.free(4096), Mp4Bytes.mdat(sound))

        assertThat(idOf(fastStart)).isEqualTo(idOf(moovLast))
        assertThat(idOf(padded)).isEqualTo(idOf(moovLast))
    }

    @Test
    fun `free skip uuid and unknown boxes and the ftyp brand are not part of the id`() {
        val reference = idOf(m4a())
        val noisy = joinBytes(
            Mp4Bytes.ftyp("mp42"),
            Mp4Bytes.box("skip", filler(77, 1)),
            Mp4Bytes.box("uuid", filler(60, 2)),
            Mp4Bytes.mdat(sound),
            Mp4Bytes.free(0),
            Mp4Bytes.moov(10),
            Mp4Bytes.box("meta", filler(20, 3)),
            Mp4Bytes.free(500),
        )

        assertThat(idOf(noisy)).isEqualTo(reference)
    }

    @Test
    fun `every mdat payload is hashed in file order and box boundaries do not matter`() {
        val first = filler(3000, 81)
        val second = filler(2000, 82)
        val fragments = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(), Mp4Bytes.mdat(first), Mp4Bytes.box("moof", filler(40, 4)), Mp4Bytes.mdat(second))
        val swapped = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(), Mp4Bytes.mdat(second), Mp4Bytes.box("moof", filler(40, 4)), Mp4Bytes.mdat(first))

        val id = idOf(fragments)

        assertThat(id.hash).isEqualTo(sha256Hex(ascii("mp4"), first, second))
        assertThat(idOf(swapped)).isNotEqualTo(id)
        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(joinBytes(first, second))))).isEqualTo(id)
    }

    @Test
    fun `a 64-bit largesize mdat and a size-0 mdat give the same id as a plain one`() {
        val plain = idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(), Mp4Bytes.mdat(sound)))

        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(), Mp4Bytes.box64("mdat", sound)))).isEqualTo(plain)
        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(), Mp4Bytes.boxToEof("mdat", sound)))).isEqualTo(plain)
        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.box64("moov", Mp4Bytes.moov().copyOfRange(8, 200)), Mp4Bytes.box64("mdat", sound))))
            .isEqualTo(plain)
    }

    @Test
    fun `a size-0 box that is not mdat ends the walk`() {
        val file = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(sound), Mp4Bytes.boxToEof("moov", filler(300, 5)))

        assertThat(idOf(file)).isEqualTo(idOf(m4a()))
    }

    @Test
    fun `flipping one mdat byte changes the id and flipping a moov byte does not`() {
        val file = m4a()
        val mdatStart = Mp4Bytes.ftyp().size + 8

        assertThat(idOf(file.flipped(mdatStart))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(mdatStart + sound.size - 1))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(mdatStart + 2500))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(file.size - 10))).isEqualTo(idOf(file))
        assertThat(idOf(file.flipped(mdatStart + sound.size + 60))).isEqualTo(idOf(file))
    }

    @Test
    fun `a truncated box falls back to the whole file`() {
        val file = m4a()

        assertThat(idOf(file.copyOf(file.size - 1)).scheme).isEqualTo("wf1")
        assertThat(idOf(file.copyOf(Mp4Bytes.ftyp().size + 8 + 100)).scheme).isEqualTo("wf1")
        assertThat(idOf(file.copyOf(file.size - 4000)).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a malformed box tree falls back to the whole file`() {
        val ftyp = Mp4Bytes.ftyp()

        // fewer than 8 bytes left after the last box
        assertThat(idOf(joinBytes(m4a(), byteArrayOf(0, 0, 0))).scheme).isEqualTo("wf1")
        // size below the 8-byte header
        assertThat(idOf(joinBytes(ftyp, be32(4), ascii("free"), Mp4Bytes.mdat(sound))).scheme).isEqualTo("wf1")
        // largesize below the 16-byte header
        assertThat(idOf(joinBytes(ftyp, be32(1), ascii("mdat"), be64(12), sound)).scheme).isEqualTo("wf1")
        // largesize beyond the file, and one with the top bit set
        assertThat(idOf(joinBytes(ftyp, be32(1), ascii("mdat"), be64(sound.size + 17L), sound)).scheme).isEqualTo("wf1")
        assertThat(idOf(joinBytes(ftyp, be32(1), ascii("mdat"), be64(Long.MIN_VALUE or 20), sound)).scheme).isEqualTo("wf1")
        // size 1 but the file ends inside the 16-byte header
        assertThat(idOf(joinBytes(ftyp, be32(1), ascii("mdat"), byteArrayOf(0, 0, 0))).scheme).isEqualTo("wf1")
        // a moov that claims more than the file holds, although mdat is complete
        assertThat(idOf(joinBytes(ftyp, Mp4Bytes.mdat(sound), be32(5000), ascii("moov"), filler(100, 6))).scheme).isEqualTo("wf1")
    }

    @Test
    fun `no mdat or an empty mdat falls back to the whole file`() {
        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov())).scheme).isEqualTo("wf1")
        assertThat(idOf(joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(ByteArray(0)), Mp4Bytes.moov())).scheme).isEqualTo("wf1")
        assertThat(idOf(Mp4Bytes.ftyp()).scheme).isEqualTo("wf1")
    }

    @Test
    fun `the id does not depend on how the source splits its reads`() {
        val file = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.moov(2000), Mp4Bytes.mdat(filler(150_000, 83)), Mp4Bytes.free(10))
        val expected = idOf(file)

        for (maxRead in intArrayOf(1, 5, 4096, 65_535, 65_537)) {
            assertThat(idOf(file, maxRead)).isEqualTo(expected)
        }
    }

    @Test
    fun `an io error inside the payload or the box headers propagates`() {
        val file = joinBytes(Mp4Bytes.ftyp(), Mp4Bytes.mdat(filler(100_000, 84)), Mp4Bytes.moov(100))

        assertThrows(IOException::class.java) { AudioPayloadHasher().identify(ArrayByteSource(file, failAt = 60_000)) }
        assertThrows(IOException::class.java) { AudioPayloadHasher().identify(ArrayByteSource(file, failAt = Mp4Bytes.ftyp().size + 8L + 100_000 + 2)) }
    }
}
