package io.sonora.core.watchsync.protocol

import io.sonora.core.watchsync.ProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [SyncMessage] to and from the UTF-8 JSON a control frame carries. Unknown fields are ignored, so a newer peer may add some. */
object SyncMessageCodec {
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(message: SyncMessage): ByteArray =
        json.encodeToString(SyncMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    /** @throws ProtocolException when [bytes] are not a known message. */
    fun decode(bytes: ByteArray): SyncMessage = try {
        json.decodeFromString(SyncMessage.serializer(), bytes.decodeToString())
    } catch (e: SerializationException) {
        throw ProtocolException("Not a sync message", e)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException("Not a sync message", e)
    }
}
