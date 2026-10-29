package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Before
import org.junit.Test

class ChannelByteSourceTest {

    private lateinit var file: File
    private lateinit var access: RandomAccessFile
    private lateinit var source: ChannelByteSource
    private val bytes = ByteArray(10_000) { (it * 31 + 7).toByte() }

    @Before
    fun setUp() {
        file = File.createTempFile("byte-source", ".bin").apply { writeBytes(bytes) }
        access = RandomAccessFile(file, "r")
        source = ChannelByteSource(access.channel, file.length(), access)
    }

    @After
    fun tearDown() {
        source.close()
        file.delete()
    }

    @Test
    fun `reports the length it was given`() {
        assertThat(source.length).isEqualTo(10_000L)
    }

    @Test
    fun `reads the bytes at a position into the buffer at an offset`() {
        val buffer = ByteArray(20)

        val read = source.read(position = 1_234, buffer = buffer, offset = 5, count = 10)

        assertThat(read).isEqualTo(10)
        assertThat(buffer.copyOfRange(5, 15)).isEqualTo(bytes.copyOfRange(1_234, 1_244))
        assertThat(buffer.copyOfRange(0, 5)).isEqualTo(ByteArray(5))
        assertThat(buffer.copyOfRange(15, 20)).isEqualTo(ByteArray(5))
    }

    @Test
    fun `reads in any order without moving a shared position`() {
        val late = ByteArray(4)
        val early = ByteArray(4)

        source.read(9_000, late, 0, 4)
        source.read(0, early, 0, 4)

        assertThat(late).isEqualTo(bytes.copyOfRange(9_000, 9_004))
        assertThat(early).isEqualTo(bytes.copyOfRange(0, 4))
    }

    @Test
    fun `a read that runs past the end returns what is left`() {
        val buffer = ByteArray(100)

        val read = source.read(9_990, buffer, 0, 100)

        assertThat(read).isEqualTo(10)
        assertThat(buffer.copyOfRange(0, 10)).isEqualTo(bytes.copyOfRange(9_990, 10_000))
    }

    @Test
    fun `a read at or after the end returns minus one`() {
        assertThat(source.read(10_000, ByteArray(8), 0, 8)).isEqualTo(-1)
        assertThat(source.read(20_000, ByteArray(8), 0, 8)).isEqualTo(-1)
    }

    @Test
    fun `a read of nothing returns zero`() {
        assertThat(source.read(5, ByteArray(8), 0, 0)).isEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a count that does not fit the buffer is refused`() {
        source.read(0, ByteArray(8), 4, 5)
    }

    @Test
    fun `closing closes the channel and the owner once`() {
        val ownerFile = RandomAccessFile(file, "r")
        var ownerClosed = 0
        val owned = ChannelByteSource(ownerFile.channel, file.length(), Closeable { ownerClosed++ })

        owned.close()
        owned.close()

        assertThat(ownerFile.channel.isOpen).isFalse()
        assertThat(ownerClosed).isEqualTo(1)
    }
}
