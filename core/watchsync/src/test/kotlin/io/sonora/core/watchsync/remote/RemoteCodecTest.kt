package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ProtocolException
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCodecTest {

    private val track = RemoteTrack(id = 7, title = "Song", artist = "Artist", album = "Album", durationMs = 215_000)

    private fun state(queue: List<RemoteQueueItem>? = null) = RemoteState(
        track = track,
        isPlaying = true,
        positionMs = 42_000,
        volume = 7,
        volumeMax = 15,
        queueIndex = 2,
        queueSize = 9,
        hasNext = true,
        queue = queue,
    )

    @Test
    fun `every command survives a round trip`() {
        val commands = listOf(
            RemoteCommand.Subscribe(withQueue = true),
            RemoteCommand.Subscribe(),
            RemoteCommand.Unsubscribe,
            RemoteCommand.SetPlaying(true),
            RemoteCommand.SetPlaying(false),
            RemoteCommand.Next,
            RemoteCommand.Previous,
            RemoteCommand.VolumeSteps(-3),
            RemoteCommand.Jump(index = 4, id = 99),
            RemoteCommand.Remove(index = 4, id = 99),
            RemoteCommand.Move(index = 4, id = 99, direction = QueueDirection.Down),
        )

        commands.forEach { assertThat(RemoteCodec.decodeCommand(RemoteCodec.encodeCommand(it))).isEqualTo(it) }
    }

    @Test
    fun `the state survives a round trip with and without a queue`() {
        val withQueue = state(listOf(RemoteQueueItem(7, "Song", "Artist"), RemoteQueueItem(8, "Other", "")))

        assertThat(RemoteCodec.decodeState(RemoteCodec.encodeState(withQueue))).isEqualTo(withQueue)
        assertThat(RemoteCodec.decodeState(RemoteCodec.encodeState(state()))).isEqualTo(state())
        assertThat(RemoteCodec.decodeState(RemoteCodec.encodeState(RemoteState()))).isEqualTo(RemoteState())
    }

    @Test
    fun `the wire form names the command type and carries no uri`() {
        val json = RemoteCodec.encodeCommand(RemoteCommand.Jump(index = 1, id = 5)).decodeToString()

        assertThat(json).contains("\"type\":\"jump\"")
        assertThat(json).doesNotContain("uri")
    }

    @Test
    fun `unknown fields are ignored so a newer peer can add some`() {
        val bytes = """{"type":"set_playing","playing":true,"future":1}""".toByteArray()

        assertThat(RemoteCodec.decodeCommand(bytes)).isEqualTo(RemoteCommand.SetPlaying(true))
    }

    @Test
    fun `an unknown type or garbage is a protocol error`() {
        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeCommand("""{"type":"format_phone"}""".toByteArray()) }
        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeCommand("not json".toByteArray()) }
        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeCommand(ByteArray(0)) }
        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeState("[]".toByteArray()) }
    }

    @Test
    fun `a message over the size limit is refused before it is parsed`() {
        val huge = ByteArray(RemoteLimits.MAX_MESSAGE_BYTES + 1) { ' '.code.toByte() }

        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeCommand(huge) }
        assertThrows(ProtocolException::class.java) { RemoteCodec.decodeState(huge) }
    }

    @Test
    fun `long texts are cut at the limit without splitting a character`() {
        val long = "a".repeat(RemoteLimits.MAX_TEXT_CHARS * 2)
        val emoji = "🎵".repeat(RemoteLimits.MAX_TEXT_CHARS) // surrogate pairs

        val decoded = RemoteCodec.decodeState(
            RemoteCodec.encodeState(state(listOf(RemoteQueueItem(1, long, emoji))).copy(track = track.copy(title = emoji))),
        )

        assertThat(decoded.queue!!.single().title.length).isEqualTo(RemoteLimits.MAX_TEXT_CHARS)
        assertThat(decoded.queue!!.single().title).endsWith("…")
        assertThat(decoded.track!!.title.last().isHighSurrogate()).isFalse()
        assertThat(decoded.queue!!.single().artist.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun `a huge queue is cut to what fits in one message`() {
        val items = (1L..1000L).map { RemoteQueueItem(Long.MAX_VALUE - it, "é".repeat(200), "€".repeat(200)) }

        val bytes = RemoteCodec.encodeState(state(items))
        val decoded = RemoteCodec.decodeState(bytes)

        assertThat(bytes.size).isAtMost(RemoteLimits.MAX_MESSAGE_BYTES)
        assertThat(decoded.queue!!.size).isAtMost(RemoteLimits.MAX_QUEUE_ITEMS)
        assertThat(decoded.queue!!.first().id).isEqualTo(Long.MAX_VALUE - 1)
        assertThat(decoded.queueSize).isEqualTo(9) // the real size is kept, only the window is cut
    }

    @Test
    fun `a worst case window of the maximum items still fits`() {
        val items = (1L..RemoteLimits.MAX_QUEUE_ITEMS.toLong()).map {
            RemoteQueueItem(Long.MAX_VALUE - it, "€".repeat(RemoteLimits.MAX_TEXT_CHARS), "€".repeat(RemoteLimits.MAX_TEXT_CHARS))
        }

        val bytes = RemoteCodec.encodeState(state(items))

        assertThat(bytes.size).isAtMost(RemoteLimits.MAX_MESSAGE_BYTES)
        assertThat(RemoteCodec.decodeState(bytes).queue).hasSize(RemoteLimits.MAX_QUEUE_ITEMS)
    }
}
