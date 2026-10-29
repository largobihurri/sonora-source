package io.sonora.core.mp3

/** Records what [Mp3Encoder] asks of the native encoder and answers with scripted results. */
internal class FakeLameBridge : LameBridge {

    data class Create(val sampleRateHz: Int, val channels: Int, val cbrKbps: Int, val vbrQuality: Int)

    data class Encode(val kind: String, val frames: Int, val pcmSize: Int, val outCapacity: Int)

    var createStatus = LameBridge.STATUS_OK
    var encodeResult = 0
    var flushResult = 0
    var tag = ByteArray(0)

    val creates = mutableListOf<Create>()
    val encodes = mutableListOf<Encode>()
    val flushCapacities = mutableListOf<Int>()
    val destroyed = mutableListOf<Long>()

    override fun create(sampleRateHz: Int, channels: Int, cbrKbps: Int, vbrQuality: Int, handleOut: LongArray): Int {
        creates += Create(sampleRateHz, channels, cbrKbps, vbrQuality)
        if (createStatus == LameBridge.STATUS_OK) handleOut[0] = HANDLE
        return createStatus
    }

    override fun encodeShorts(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int {
        check(handle == HANDLE)
        encodes += Encode("short", frames, pcm.size, out.size)
        return fill(out, encodeResult)
    }

    override fun encodeFloats(handle: Long, pcm: FloatArray, frames: Int, out: ByteArray): Int {
        check(handle == HANDLE)
        encodes += Encode("float", frames, pcm.size, out.size)
        return fill(out, encodeResult)
    }

    override fun flush(handle: Long, out: ByteArray): Int {
        check(handle == HANDLE)
        flushCapacities += out.size
        return fill(out, flushResult)
    }

    override fun lametagFrame(handle: Long, out: ByteArray): Int {
        check(handle == HANDLE)
        if (out.size >= tag.size) tag.copyInto(out)
        return tag.size
    }

    override fun destroy(handle: Long) {
        destroyed += handle
    }

    /** Writes bytes 1, 2, 3... for a positive [result], like a native call that produced that many bytes. */
    private fun fill(out: ByteArray, result: Int): Int {
        for (i in 0 until result) out[i] = (i + 1).toByte()
        return result
    }

    companion object {
        const val HANDLE = 0x5EED_0001L
    }
}
