package io.sonora.core.transcoder.mp4

import com.google.common.truth.Truth.assertThat
import io.sonora.core.transcoder.MemoryChannel
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4MetadataInjectorTest {

    private val mdat = ByteArray(64) { it.toByte() }
    private val ilst = Mp4Boxes.box("ilst", Mp4Boxes.box("©nam", Mp4Boxes.box("data", Mp4Boxes.ints(1, 0), "Aria".toByteArray())))

    private fun inject(file: ByteArray, ilst: ByteArray = this.ilst): ByteArray {
        val source = MemoryChannel().apply { write(ByteBuffer.wrap(file)); position(0) }
        val target = MemoryChannel()
        Mp4MetadataInjector.inject(source, target, ilst)
        return target.bytes()
    }

    /** The bytes each chunk offset points at, which must not change when the moov grows. */
    private fun chunks(file: ByteArray) = Mp4Boxes.chunkOffsets(file).map { file.copyOfRange(it.toInt(), it.toInt() + 4).toList() }

    private fun assertTagged(out: ByteArray) {
        val meta = Mp4Boxes.find(out, "moov/udta/meta")!!
        assertThat(meta.payload.copyOfRange(0, 4)).isEqualTo(ByteArray(4))
        val hdlr = Mp4Boxes.find(out, "moov/udta/meta/hdlr")!!
        assertThat(String(hdlr.payload, 8, 4, Charsets.ISO_8859_1)).isEqualTo("mdir")
        assertThat(Mp4Boxes.find(out, "moov/udta/meta/ilst")!!.payload).isEqualTo(ilst.copyOfRange(8, ilst.size))
    }

    @Test
    fun `with the moov ahead of the media every chunk offset moves by the growth`() {
        val file = Mp4Boxes.file(moovFirst = true, mdat = mdat)

        val out = inject(file)

        assertTagged(out)
        assertThat(chunks(out)).isEqualTo(chunks(file))
        val growth = out.size - file.size
        assertThat(Mp4Boxes.chunkOffsets(out)).isEqualTo(Mp4Boxes.chunkOffsets(file).map { it + growth })
        assertThat(Mp4Boxes.parse(out).map { it.type }).containsExactly("ftyp", "moov", "mdat").inOrder()
    }

    @Test
    fun `with the moov after the media the offsets stay`() {
        val file = Mp4Boxes.file(moovFirst = false, mdat = mdat)

        val out = inject(file)

        assertTagged(out)
        assertThat(Mp4Boxes.chunkOffsets(out)).isEqualTo(Mp4Boxes.chunkOffsets(file))
        assertThat(chunks(out)).isEqualTo(chunks(file))
    }

    @Test
    fun `64-bit chunk offsets move too`() {
        val file = Mp4Boxes.file(moovFirst = true, mdat = mdat, sixtyFour = true)

        val out = inject(file)

        assertThat(chunks(out)).isEqualTo(chunks(file))
        assertThat(Mp4Boxes.chunkOffsets(out).first()).isGreaterThan(Mp4Boxes.chunkOffsets(file).first())
    }

    @Test
    fun `an existing udta keeps its other boxes and loses its old meta`() {
        val oldMeta = Mp4Boxes.box("meta", Mp4Boxes.ints(0), Mp4Boxes.box("ilst"))
        val udta = Mp4Boxes.box("udta", Mp4Boxes.box("©xyz", "+43.0-002.0/".toByteArray()), oldMeta)
        val file = Mp4Boxes.file(moovFirst = true, mdat = mdat, udta = udta)

        val out = inject(file)

        val children = Mp4Boxes.parse(Mp4Boxes.find(out, "moov/udta")!!.payload).map { it.type }
        assertThat(children).containsExactly("©xyz", "meta").inOrder()
        assertTagged(out)
        assertThat(chunks(out)).isEqualTo(chunks(file))
    }

    @Test
    fun `a file without a moov is rejected`() {
        val file = Mp4Boxes.box("ftyp", Mp4Boxes.ints(0)) + Mp4Boxes.box("mdat", mdat)
        assertThrows(java.io.IOException::class.java) { inject(file) }
    }
}
