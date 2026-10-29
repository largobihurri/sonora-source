package io.sonora.core.watchsync.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.ProtocolException
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncMessageCodecTest {

    private val track = ManifestTrack(
        id = "0123456789abcdef",
        title = "Song",
        artist = "Artist",
        albumArtist = null,
        album = "Album",
        trackNumber = 3,
        discNumber = 1,
        durationMs = 215_000,
        sizeBytes = 5_300_000,
        sha256 = "a".repeat(64),
    )

    private fun roundTrip(message: SyncMessage): SyncMessage = SyncMessageCodec.decode(SyncMessageCodec.encode(message))

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            SyncMessage.Hello(1),
            SyncMessage.Query,
            SyncMessage.FreeSpace(freeBytes = 9_000_000_000, totalBytes = 23_000_000_000, reserveBytes = 256_000_000),
            SyncMessage.Contents(listOf(track, track.copy(id = "fedcba9876543210", album = null))),
            SyncMessage.Contents(listOf(track), more = true),
            SyncMessage.Offer(track),
            SyncMessage.Have("0123456789abcdef"),
            SyncMessage.Request("0123456789abcdef", offset = 1_048_576),
            SyncMessage.Reject("0123456789abcdef", SyncError.NO_SPACE),
            SyncMessage.Progress("0123456789abcdef", receivedBytes = 42),
            SyncMessage.Done("0123456789abcdef"),
            SyncMessage.Failed("0123456789abcdef", SyncError.CHECKSUM_MISMATCH),
            SyncMessage.Remove(listOf("0123456789abcdef", "fedcba9876543210")),
            SyncMessage.Removed(listOf("0123456789abcdef")),
            SyncMessage.End,
        )

        messages.forEach { assertThat(roundTrip(it)).isEqualTo(it) }
    }

    @Test
    fun `the wire form names the message type`() {
        val text = SyncMessageCodec.encode(SyncMessage.Done("0123456789abcdef")).decodeToString()

        assertThat(text).contains("\"type\":\"done\"")
    }

    @Test
    fun `fields a newer peer adds are ignored`() {
        val text = """{"type":"done","trackId":"0123456789abcdef","futureField":[1,2,3]}"""

        assertThat(SyncMessageCodec.decode(text.toByteArray())).isEqualTo(SyncMessage.Done("0123456789abcdef"))
    }

    @Test
    fun `an unknown message type is a protocol error`() {
        val text = """{"type":"teleport"}"""

        assertThrows(ProtocolException::class.java) { SyncMessageCodec.decode(text.toByteArray()) }
    }

    @Test
    fun `bytes that are not a message are a protocol error`() {
        assertThrows(ProtocolException::class.java) { SyncMessageCodec.decode("not json".toByteArray()) }
        assertThrows(ProtocolException::class.java) { SyncMessageCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `a message missing a required field is a protocol error`() {
        assertThrows(ProtocolException::class.java) {
            SyncMessageCodec.decode("""{"type":"request","trackId":"0123456789abcdef"}""".toByteArray())
        }
    }

    @Test
    fun `contents from a peer that has no pages is the last page`() {
        val older = """{"type":"contents","tracks":[]}"""

        assertThat(SyncMessageCodec.decode(older.toByteArray())).isEqualTo(SyncMessage.Contents(emptyList(), more = false))
    }
}
