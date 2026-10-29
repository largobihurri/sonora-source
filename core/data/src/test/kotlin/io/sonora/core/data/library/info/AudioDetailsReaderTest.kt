package io.sonora.core.data.library.info

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.TagInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The File Info read path on real files. A SAF document usually cannot be reopened by its `/proc/self/fd` path under
 * scoped storage, so the reader must fall back to a copy, as the library scanner does, instead of reporting the tags
 * as unavailable.
 */
class AudioDetailsReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val reader by lazy { AudioDetailsReader(folder.newFolder("cache"), imageSize = { null }) }

    /** A 24-bit stereo AIFF with an ID3 chunk written by JAudioTagger itself, like a tagged library file. */
    private fun taggedAiff(): File {
        val file = folder.newFile("01 DELIVER ME - IZYAH DAVIS.aiff").apply { writeBytes(aiff(2, 44_100, 24, 4_410)) }
        JAudioTagger.read {
            val audio = AudioFileIO.readAs(file, "aiff")
            audio.tagOrCreateAndSetDefault.apply {
                setField(FieldKey.TITLE, "DELIVER ME / IZYAH DAVIS")
                setField(FieldKey.ARTIST, "DUB INVASION RECORDS")
            }
            audio.commit()
        }
        return file
    }

    private fun copyOf(file: File): (java.io.OutputStream) -> Unit = { out -> file.inputStream().use { it.copyTo(out) } }

    @Test
    fun `a tagged AIFF that cannot be opened by path is read from a copy, tags included`() {
        val file = taggedAiff()

        val details = reader.read("aiff", direct = null, copyTo = copyOf(file))

        assertThat(details.tags?.title).isEqualTo("DELIVER ME / IZYAH DAVIS")
        assertThat(details.tags?.artist).isEqualTo("DUB INVASION RECORDS")
        assertThat(details.audio?.sampleFormat).isEqualTo(SampleFormat.Integer(24))
        assertThat(details.aiff?.sampleRateHz).isEqualTo(44_100)
        assertThat(folder.root.resolve("cache").listFiles()).isEmpty() // the copy is gone
    }

    @Test
    fun `a tagged AIFF read by path gives the same details`() {
        val file = taggedAiff()

        val details = reader.read("aiff", direct = file, copyTo = { error("no copy needed") })

        assertThat(details.tags?.title).isEqualTo("DELIVER ME / IZYAH DAVIS")
        assertThat(details.aiff?.channelCount).isEqualTo(2)
    }

    @Test
    fun `a file read without any tag reports no tags, not unavailable ones`() {
        val file = folder.newFile("plain.aiff").apply { writeBytes(aiff(2, 44_100, 16, 441)) }

        val details = reader.read("aiff", direct = null, copyTo = copyOf(file))

        assertThat(details.tags).isEqualTo(TagInfo())
        assertThat(details.audio).isNotNull()
    }

    @Test
    fun `a file JAudioTagger cannot parse reports tags as unavailable`() {
        val garbage = folder.newFile("broken.flac").apply { writeBytes(ByteArray(512) { 7 }) }

        val details = reader.read("flac", direct = garbage, copyTo = { error("no copy needed") })

        assertThat(details.tags).isNull()
        assertThat(details.audio).isNull()
    }

    @Test
    fun `a copy that fails is an error, not unavailable tags`() {
        assertThrows(IOException::class.java) {
            reader.read("aiff", direct = null, copyTo = { throw IOException("provider gone") })
        }
    }

    private fun aiff(channels: Int, rate: Int, bits: Int, frames: Int): ByteArray {
        val comm = ByteBuffer.allocate(18).apply {
            putShort(channels.toShort()); putInt(frames); putShort(bits.toShort()); put(extended(rate))
        }.array()
        val ssnd = ByteArray(8) + ByteArray(frames * channels * bits / 8)
        val body = ByteArrayOutputStream().apply {
            write("AIFF".toByteArray())
            write("COMM".toByteArray()); write(be32(comm.size)); write(comm)
            write("SSND".toByteArray()); write(be32(ssnd.size)); write(ssnd)
        }.toByteArray()
        return "FORM".toByteArray() + be32(body.size) + body
    }

    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun extended(rate: Int): ByteArray {
        val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - highestBit)
        return ByteBuffer.allocate(10).putShort((16_383 + highestBit).toShort()).putLong(mantissa).array()
    }
}
