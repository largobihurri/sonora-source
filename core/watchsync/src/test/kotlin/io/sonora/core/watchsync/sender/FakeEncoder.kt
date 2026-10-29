package io.sonora.core.watchsync.sender

import io.sonora.core.watchsync.TrackEncodingException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A [TrackEncoder] that "encodes" into deterministic bytes, keeps a finished file like the real one, and records its use. */
class FakeEncoder(private val dir: File) : TrackEncoder {
    val encoded = CopyOnWriteArrayList<String>()
    val released = CopyOnWriteArrayList<String>()
    var sizeOf: (SendCandidate) -> Int = { 20_000 }
    var failFor: Set<String> = emptySet()

    fun bytesFor(candidate: SendCandidate): ByteArray =
        ByteArray(sizeOf(candidate)) { ((it * 31) xor candidate.id.hashCode()).toByte() }

    override suspend fun encode(candidate: SendCandidate, onProgress: (Float) -> Unit): File {
        if (candidate.id in failFor) throw TrackEncodingException("Cannot decode ${candidate.id}")
        val file = File(dir, "${candidate.id}.opus")
        onProgress(0f)
        if (!file.exists()) {
            encoded += candidate.id
            file.writeBytes(bytesFor(candidate))
        }
        onProgress(1f)
        return file
    }

    override fun release(candidate: SendCandidate) {
        released += candidate.id
        File(dir, "${candidate.id}.opus").delete()
    }
}
