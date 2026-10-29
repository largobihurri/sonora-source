package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.ByteSource
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioPayloadHasherTest {

    private class Cancelled : RuntimeException()

    private fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun idOf(bytes: ByteArray, maxRead: Int = Int.MAX_VALUE): AudioId =
        AudioPayloadHasher().identify(ArrayByteSource(bytes, maxRead))

    @Test
    fun `an unknown format is hashed whole under the wf1 scheme`() {
        val bytes = filler(100_000, 1)

        val id = idOf(bytes)

        assertThat(id.scheme).isEqualTo(AudioId.WHOLE_FILE_V1)
        assertThat(id.hash).isEqualTo(sha256Hex(bytes))
    }

    @Test
    fun `an empty file and a tiny file never throw`() {
        assertThat(idOf(ByteArray(0))).isEqualTo(AudioId.of("wf1", sha256Hex()))
        assertThat(idOf(byteArrayOf(0x49)).scheme).isEqualTo("wf1")
        assertThat(idOf(ascii("ID3")).scheme).isEqualTo("wf1")
        assertThat(idOf(ascii("fLaC")).scheme).isEqualTo("wf1")
        assertThat(idOf(ascii("RIFF....WAVE")).scheme).isEqualTo("wf1")
        assertThat(idOf(ascii("FORM....AIFF")).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a whole-file id changes with any byte including tags`() {
        val bytes = filler(5000, 2)

        assertThat(idOf(bytes.flipped(0))).isNotEqualTo(idOf(bytes))
        assertThat(idOf(bytes.flipped(4999))).isNotEqualTo(idOf(bytes))
    }

    @Test
    fun `a source shorter than its claimed length falls back without throwing`() {
        val mp3 = joinBytes(TagBytes.id3v2(100), Mp3Bytes.stream(10))
        val source = ArrayByteSource(mp3, claimedLength = mp3.size + 5000L)

        val id = AudioPayloadHasher().identify(source)

        assertThat(id.scheme).isEqualTo("wf1")
    }

    @Test
    fun `a source of unknown length is hashed whole`() {
        val bytes = joinBytes(TagBytes.id3v2(100), Mp3Bytes.stream(10))

        val id = AudioPayloadHasher().identify(ArrayByteSource(bytes, claimedLength = -1))

        assertThat(id).isEqualTo(AudioId.of("wf1", sha256Hex(bytes)))
    }

    @Test
    fun `the id does not depend on how the source splits its reads`() {
        val mp3 = joinBytes(TagBytes.id3v2(700), Mp3Bytes.stream(500), TagBytes.id3v1())
        val unknown = filler(150_000, 3)
        val expectedMp3 = idOf(mp3)
        val expectedUnknown = idOf(unknown)

        for (maxRead in intArrayOf(1, 7, 4096, 65_535, 65_536, 65_537)) {
            assertThat(idOf(mp3, maxRead)).isEqualTo(expectedMp3)
            assertThat(idOf(unknown, maxRead)).isEqualTo(expectedUnknown)
        }
    }

    @Test
    fun `the id does not depend on where the 64 KiB chunks fall in the file`() {
        val audio = Mp3Bytes.stream(400)
        val bare = idOf(audio)

        for (tagBody in intArrayOf(0, 1, 63, 64, 65_000, 65_526, 65_527, 65_536, 70_000)) {
            val tagged = joinBytes(TagBytes.id3v2(tagBody), audio, TagBytes.id3v1())

            assertThat(idOf(tagged)).isEqualTo(bare)
        }
    }

    @Test
    fun `cancellation is checked once per chunk and stops the hash`() {
        val bytes = filler(1 shl 20, 4)
        val source = ArrayByteSource(bytes)
        var checks = 0

        assertThrows(Cancelled::class.java) {
            AudioPayloadHasher().identify(source) {
                checks++
                if (checks == 4) throw Cancelled()
            }
        }

        assertThat(checks).isEqualTo(4)
        assertThat(source.bytesRead).isLessThan(bytes.size.toLong())
    }

    @Test
    fun `cancellation also stops a payload hash`() {
        val mp3 = joinBytes(TagBytes.id3v2(100), Mp3Bytes.stream(2500))
        var checks = 0

        assertThrows(Cancelled::class.java) {
            AudioPayloadHasher().identify(ArrayByteSource(mp3)) {
                checks++
                if (checks == 5) throw Cancelled()
            }
        }
    }

    @Test
    fun `a full pass checks cancellation at least once per 64 KiB`() {
        val bytes = filler(1 shl 20, 5)
        var checks = 0

        AudioPayloadHasher().identify(ArrayByteSource(bytes)) { checks++ }

        assertThat(checks).isAtLeast(16)
        assertThat(checks).isAtMost(20)
    }

    @Test
    fun `an io error is propagated and not turned into a fallback`() {
        val mp3 = joinBytes(TagBytes.id3v2(100), Mp3Bytes.stream(300))

        assertThrows(IOException::class.java) {
            AudioPayloadHasher().identify(ArrayByteSource(mp3, failAt = 50_000))
        }
        assertThrows(IOException::class.java) {
            AudioPayloadHasher().identify(ArrayByteSource(filler(200_000, 6), failAt = 150_000))
        }
    }

    @Test
    fun `the default walkers are flac mp3 wav aiff mp4 and ogg in that order`() {
        assertThat(AudioPayloadHasher.defaultWalkers().map { it.containerTag })
            .containsExactly("flac", "mp3", "wav", "aiff", "mp4", "ogg").inOrder()
    }

    @Test
    fun `cancellation is checked while a walker reads and stops a long walk`() {
        val packets = listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(10), OggBytes.vorbisSetup()) + OggBytes.packets(List(3000) { 100 })
        val source = ArrayByteSource(OggBytes.stream(packets, segmentsPerPage = 1))
        var checks = 0

        assertThrows(Cancelled::class.java) {
            AudioPayloadHasher().identify(source) {
                checks++
                if (checks == 20) throw Cancelled()
            }
        }

        assertThat(checks).isEqualTo(20)
        assertThat(source.readCalls).isLessThan(100)
    }

    @Test
    fun `a container whose markers sit at other offsets is not taken for mp4 or ogg`() {
        assertThat(idOf(ascii("....ftyp")).scheme).isEqualTo("wf1")
        assertThat(idOf(joinBytes(byteArrayOf(0, 0, 0), ascii("ftyp"), filler(40, 9))).scheme).isEqualTo("wf1")
        assertThat(idOf(joinBytes(filler(8, 9), ascii("OggS"), filler(40, 9))).scheme).isEqualTo("wf1")
    }

    private class FixedWalker(
        override val containerTag: String,
        private val ranges: List<LongRange>?,
        private val matchesAll: Boolean = true,
    ) : PayloadWalker {
        var asked = 0
        override fun matches(prefix: ByteArray): Boolean = matchesAll
        override fun payloadRanges(source: ByteSource): List<LongRange>? {
            asked++
            return ranges
        }
    }

    @Test
    fun `the first walker that matches and returns ranges decides and its tag is hashed first`() {
        val bytes = ascii("0123456789abcdef")
        val skipped = FixedWalker("no", ranges = null)
        val used = FixedWalker("zz", ranges = listOf(2L..4L, 10L..12L))
        val later = FixedWalker("later", ranges = listOf(0L..1L))

        val id = AudioPayloadHasher(listOf(skipped, used, later)).identify(ArrayByteSource(bytes))

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(sha256Hex(ascii("zz"), ascii("234"), ascii("abc")))
        assertThat(skipped.asked).isEqualTo(1)
        assertThat(later.asked).isEqualTo(0)
    }

    @Test
    fun `a walker that does not match is not asked`() {
        val walker = FixedWalker("x", ranges = listOf(0L..3L), matchesAll = false)

        val id = AudioPayloadHasher(listOf(walker)).identify(ArrayByteSource(ascii("0123456789")))

        assertThat(id.scheme).isEqualTo("wf1")
        assertThat(walker.asked).isEqualTo(0)
    }

    @Test
    fun `ranges outside the file or out of order fall back to the whole file`() {
        val bytes = ascii("0123456789")

        fun scheme(ranges: List<LongRange>) =
            AudioPayloadHasher(listOf(FixedWalker("x", ranges))).identify(ArrayByteSource(bytes)).scheme

        assertThat(scheme(listOf(0L..10L))).isEqualTo("wf1")
        assertThat(scheme(listOf(-1L..3L))).isEqualTo("wf1")
        assertThat(scheme(listOf(5L..7L, 2L..3L))).isEqualTo("wf1")
        assertThat(scheme(listOf(2L..5L, 4L..6L))).isEqualTo("wf1")
        assertThat(scheme(emptyList())).isEqualTo("wf1")
        assertThat(scheme(listOf(0L..3L, 5L..6L))).isEqualTo("ap1")
    }

    @Test
    fun `the same audio in different containers gets different ids`() {
        val sound = filler(3000, 8)
        val wav = WavBytes.riff(WavBytes.fmt(), WavBytes.data(sound))
        val aiff = AiffBytes.form(AiffBytes.comm(), AiffBytes.ssnd(sound))

        assertThat(idOf(wav)).isNotEqualTo(idOf(aiff))
    }
}
