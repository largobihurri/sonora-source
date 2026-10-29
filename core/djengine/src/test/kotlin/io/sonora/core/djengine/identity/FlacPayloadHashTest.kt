package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.security.MessageDigest
import org.junit.Test

class FlacPayloadHashTest {

    private val frames = FlacBytes.frames(10)

    private fun flac(vararg extra: Pair<Int, ByteArray>, streamInfo: ByteArray = FlacBytes.streamInfo()): ByteArray =
        joinBytes(FlacBytes.header(listOf(FlacBytes.STREAMINFO to streamInfo) + extra), frames)

    private fun idOf(bytes: ByteArray): AudioId = AudioPayloadHasher().identify(ArrayByteSource(bytes))

    @Test
    fun `the id is the sha256 of the tag flac followed by the frames`() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ascii("flac"))
        digest.update(frames)

        val id = idOf(flac())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `adding editing and removing metadata blocks keeps the id`() {
        val bare = idOf(flac())

        assertThat(idOf(flac(FlacBytes.VORBIS_COMMENT to filler(300, 1)))).isEqualTo(bare)
        assertThat(idOf(flac(FlacBytes.VORBIS_COMMENT to filler(80, 2), FlacBytes.PICTURE to filler(5000, 3)))).isEqualTo(bare)
        assertThat(idOf(flac(FlacBytes.PADDING to ByteArray(4096)))).isEqualTo(bare)
        assertThat(idOf(flac(FlacBytes.VORBIS_COMMENT to ByteArray(0)))).isEqualTo(bare)
    }

    @Test
    fun `the streaminfo block is skipped too`() {
        assertThat(idOf(flac(streamInfo = FlacBytes.streamInfo(seed = 77)))).isEqualTo(idOf(flac()))
    }

    @Test
    fun `a leading ID3v2 tag and a trailing ID3v1 tag keep the id`() {
        val bare = idOf(flac())

        assertThat(idOf(joinBytes(TagBytes.id3v2(200), flac()))).isEqualTo(bare)
        assertThat(idOf(joinBytes(TagBytes.id3v2(20), TagBytes.id3v2(30, footer = true), flac()))).isEqualTo(bare)
        assertThat(idOf(joinBytes(flac(), TagBytes.id3v1()))).isEqualTo(bare)
        assertThat(idOf(joinBytes(TagBytes.id3v2(100), flac(), TagBytes.id3v1()))).isEqualTo(bare)
    }

    @Test
    fun `flipping one audio byte changes the id`() {
        val file = flac(FlacBytes.VORBIS_COMMENT to filler(50, 4))
        val audioStart = file.size - frames.size

        assertThat(idOf(file.flipped(audioStart))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(audioStart + 3000))).isNotEqualTo(idOf(file))
        assertThat(idOf(file.flipped(file.size - 1))).isNotEqualTo(idOf(file))
    }

    @Test
    fun `a metadata block longer than the file falls back to the whole file`() {
        val truncated = flac(FlacBytes.PICTURE to filler(5000, 5)).copyOf(400)

        assertThat(idOf(truncated).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a file with no frames after the metadata falls back to the whole file`() {
        val noFrames = FlacBytes.header(listOf(FlacBytes.STREAMINFO to FlacBytes.streamInfo()))

        assertThat(idOf(noFrames).scheme).isEqualTo("wf1")
    }

    @Test
    fun `metadata that never sets the last block flag falls back to the whole file`() {
        val noLast = joinBytes(ascii("fLaC"), FlacBytes.block(FlacBytes.STREAMINFO, FlacBytes.streamInfo(), last = false))

        assertThat(idOf(noLast).scheme).isEqualTo("wf1")
    }

    @Test
    fun `an invalid block type falls back to the whole file`() {
        val bad = joinBytes(ascii("fLaC"), FlacBytes.block(127, ByteArray(4), last = true), frames)

        assertThat(idOf(bad).scheme).isEqualTo("wf1")
    }

    @Test
    fun `audio that does not start with a frame sync falls back to the whole file`() {
        val noSync = joinBytes(FlacBytes.header(listOf(FlacBytes.STREAMINFO to FlacBytes.streamInfo())), filler(900, 6))

        assertThat(idOf(noSync).scheme).isEqualTo("wf1")
    }

    @Test
    fun `an ID3 tag before an MP3 stream is not mistaken for flac`() {
        val mp3 = joinBytes(TagBytes.id3v2(100), Mp3Bytes.stream(6))

        val id = idOf(mp3)

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id).isEqualTo(idOf(Mp3Bytes.stream(6)))
    }
}
