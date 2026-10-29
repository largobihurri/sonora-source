package io.sonora.core.watchsync.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.SyncLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertThrows
import org.junit.Test

class FramesTest {

    private fun written(block: (FrameWriter) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        block(FrameWriter(bytes))
        return bytes.toByteArray()
    }

    @Test
    fun `control and data frames read back in order`() {
        val data = ByteArray(1000) { it.toByte() }
        val bytes = written {
            it.writeControl(SyncMessage.Query)
            it.writeData(data, 0, data.size)
            it.writeControl(SyncMessage.End)
        }

        val reader = FrameReader(ByteArrayInputStream(bytes))

        assertThat(reader.read()).isEqualTo(Frame.Control(SyncMessage.Query))
        val frame = reader.read() as Frame.Data
        assertThat(frame.bytes).isEqualTo(data)
        assertThat(reader.read()).isEqualTo(Frame.Control(SyncMessage.End))
        assertThat(reader.read()).isNull()
    }

    @Test
    fun `a data frame writes only the requested slice`() {
        val buffer = byteArrayOf(9, 1, 2, 3, 9)
        val bytes = written { it.writeData(buffer, 1, 3) }

        val frame = FrameReader(ByteArrayInputStream(bytes)).read() as Frame.Data

        assertThat(frame.bytes).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `an empty stream ends cleanly`() {
        assertThat(FrameReader(ByteArrayInputStream(ByteArray(0))).read()).isNull()
    }

    @Test
    fun `a stream cut inside a header is an end of stream error`() {
        val bytes = written { it.writeControl(SyncMessage.End) }.copyOf(3)

        assertThrows(EOFException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }

    @Test
    fun `a stream cut inside a payload is an end of stream error`() {
        val data = ByteArray(100)
        val bytes = written { it.writeData(data, 0, data.size) }.copyOf(50)

        assertThrows(EOFException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }

    @Test
    fun `an unknown frame kind is a protocol error`() {
        val bytes = byteArrayOf(7, 0, 0, 0, 0)

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }

    @Test
    fun `a data frame longer than the limit is refused before it is read`() {
        val tooLong = SyncLimits.MAX_DATA_FRAME_BYTES + 1
        val bytes = byteArrayOf(2, (tooLong ushr 24).toByte(), (tooLong ushr 16).toByte(), (tooLong ushr 8).toByte(), tooLong.toByte())

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }

    @Test
    fun `a negative length is a protocol error`() {
        val bytes = byteArrayOf(1, -1, -1, -1, -1)

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }

    @Test
    fun `a writer refuses a data frame over the limit`() {
        val big = ByteArray(SyncLimits.MAX_DATA_FRAME_BYTES + 1)

        assertThrows(IllegalArgumentException::class.java) { written { it.writeData(big, 0, big.size) } }
    }

    @Test
    fun `a control frame that is not a message is a protocol error`() {
        val body = "{}".toByteArray()
        val bytes = byteArrayOf(1, 0, 0, 0, body.size.toByte()) + body

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).read() }
    }
}
