package io.sonora.core.watchsync.remote

import io.sonora.core.watchsync.ProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The remote's messages to and from the UTF-8 JSON one `MessageClient` message carries. Unknown fields are ignored, so a
 * newer peer may add some. Both directions refuse anything over [RemoteLimits.MAX_MESSAGE_BYTES] before parsing it.
 */
object RemoteCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encodeCommand(command: RemoteCommand): ByteArray =
        json.encodeToString(RemoteCommand.serializer(), command).toByteArray(Charsets.UTF_8)

    /** @throws ProtocolException when [bytes] are not a known command, or too big. */
    fun decodeCommand(bytes: ByteArray): RemoteCommand =
        decode(bytes) { json.decodeFromString(RemoteCommand.serializer(), it) }

    /**
     * The state as bytes that always fit in one message: texts are cut at [RemoteLimits.MAX_TEXT_CHARS], the queue window at
     * [RemoteLimits.MAX_QUEUE_ITEMS], and if it is still too big the window is halved until it fits.
     */
    fun encodeState(state: RemoteState): ByteArray {
        var candidate = state.trimmed()
        var bytes = encodeFull(candidate)
        while (bytes.size > RemoteLimits.MAX_MESSAGE_BYTES && !candidate.queue.isNullOrEmpty()) {
            candidate = candidate.copy(queue = candidate.queue!!.take(candidate.queue!!.size / 2))
            bytes = encodeFull(candidate)
        }
        return bytes
    }

    /** @throws ProtocolException when [bytes] are not a state, or too big. */
    fun decodeState(bytes: ByteArray): RemoteState =
        decode(bytes) { json.decodeFromString(RemoteState.serializer(), it) }

    private fun encodeFull(state: RemoteState): ByteArray =
        json.encodeToString(RemoteState.serializer(), state).toByteArray(Charsets.UTF_8)

    private inline fun <T> decode(bytes: ByteArray, parse: (String) -> T): T {
        if (bytes.size > RemoteLimits.MAX_MESSAGE_BYTES) throw ProtocolException("Remote message too big: ${bytes.size} bytes")
        return try {
            parse(bytes.decodeToString())
        } catch (e: SerializationException) {
            throw ProtocolException("Not a remote message", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Not a remote message", e)
        }
    }

    private fun RemoteState.trimmed(): RemoteState = copy(
        track = track?.let { it.copy(title = it.title.cutAt(), artist = it.artist.cutAt(), album = it.album.cutAt()) },
        queue = queue?.take(RemoteLimits.MAX_QUEUE_ITEMS)?.map { it.copy(title = it.title.cutAt(), artist = it.artist.cutAt()) },
    )

    /** At most [RemoteLimits.MAX_TEXT_CHARS] chars, ending in an ellipsis when cut, never inside a surrogate pair. */
    private fun String.cutAt(): String {
        val max = RemoteLimits.MAX_TEXT_CHARS
        if (length <= max) return this
        var end = max - 1
        if (this[end - 1].isHighSurrogate()) end--
        return substring(0, end) + ELLIPSIS
    }

    private const val ELLIPSIS = "…"
}
