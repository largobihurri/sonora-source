package io.sonora.core.playback.loop

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djloop.LoopBlend
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Test

/**
 * A loop on a 16-bit big endian stream, the PCM Media3 decodes AIFF to: the loop sink sits before `DefaultAudioSink`, so
 * it sees the decoder's own byte order. The sink below must get exactly the bytes and the format it would get without
 * loops, and a loop must repeat the captured bytes.
 */
@OptIn(UnstableApi::class)
class LoopAudioSinkBigEndianTest {

    private val rate = 48_000
    private val basePts = 1_000_000_000_000L
    private val delay = 1_105
    private val frameBytes = 4

    /** The sink below: keeps the bytes of everything it is handed. */
    private class RecordingInner {
        val bytes = ByteArrayOutputStream()
        var position = C.TIME_UNSET
        var configured: AudioSink.AudioSinkConfig? = null

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "handleBuffer" -> {
                        val buffer = args[0] as ByteBuffer
                        val chunk = ByteArray(buffer.remaining())
                        buffer.get(chunk)
                        bytes.write(chunk)
                        true
                    }
                    "getCurrentPositionUs" -> position
                    "configure" -> {
                        configured = args[0] as AudioSink.AudioSinkConfig
                        null
                    }
                    "isEnded" -> false
                    "hasPendingData" -> false
                    "setListener", "setOutputStreamOffsetUs", "flush", "reset", "play", "pause", "playToEndOfStream",
                    "handleDiscontinuity", "setVolume",
                    -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as AudioSink
    }

    private val inner = RecordingInner()
    private val hub = LoopHub().apply { enabled = true; context = LoopContext(null, quantize = false) }
    private val sink = LoopAudioSink(inner.sink, hub, null)

    private val format: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        .setSampleRate(rate)
        .setChannelCount(2)
        .setEncoderDelay(delay)
        .build()

    /** Sample of channel [channel] of source frame [k]: a ramp with a negative half, so every byte pattern shows up. */
    private fun sample(k: Int, channel: Int): Int {
        val v = (k * 37 % 60_000) - 30_000
        return if (channel == 0) v else -v / 2
    }

    private fun source(from: Int, frames: Int): ByteArray {
        val out = ByteArray(frames * frameBytes)
        for (i in 0 until frames) {
            for (channel in 0..1) {
                val v = sample(from + i, channel)
                out[i * frameBytes + channel * 2] = (v shr 8).toByte()
                out[i * frameBytes + channel * 2 + 1] = v.toByte()
            }
        }
        return out
    }

    private var fed = 0

    private fun pts() = basePts + fed * 1_000_000L / rate

    private fun heardAt(o: Long): Long = basePts + (o - delay) * 1_000_000L / rate

    private fun output(fromFrame: Int, frames: Int): ByteArray =
        inner.bytes.toByteArray().copyOfRange(fromFrame * frameBytes, (fromFrame + frames) * frameBytes)

    private fun feed(frames: Int) {
        var left = frames
        while (left > 0) {
            val n = minOf(480, left)
            val b = ByteBuffer.wrap(source(fed, n))
            var guard = 0
            while (!sink.handleBuffer(b, pts(), 1) && guard++ < 20) Unit
            fed += n
            left -= n
        }
    }

    @Test
    fun `the sink below is configured with the stream's own big endian format and gets the bytes untouched`() {
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        feed(4_800)

        assertThat(inner.configured!!.format.pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        assertThat(hub.snapshot.available).isTrue()
        assertThat(inner.bytes.size()).isEqualTo(4_800 * frameBytes)
        assertThat(output(0, 4_800)).isEqualTo(source(0, 4_800))
    }

    @Test
    fun `a loop on a big endian stream repeats the captured bytes, outside the blend`() {
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        feed(19_200)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(10_000)
        sink.getCurrentPositionUs(false)
        hub.post(LoopRequest.MarkOut)
        inner.position = heardAt(18_000)
        // The decoder is ahead of what is heard: the loop joins at the frontier, where the output stands now.
        val join = inner.bytes.size() / frameBytes
        var guard = 0
        while (guard++ < 400 && !hub.snapshot.engaged) {
            val b = ByteBuffer.wrap(source(fed, 480))
            if (sink.handleBuffer(b, pts(), 1)) fed += 480
        }
        assertThat(hub.snapshot.engaged).isTrue()
        // Keep the decoder running while the loop plays.
        guard = 0
        while (guard++ < 400 && inner.bytes.size() / frameBytes < join + 3 * 8_000) {
            val b = ByteBuffer.wrap(source(fed, 480))
            if (sink.handleBuffer(b, pts(), 1)) fed += 480
        }

        val blend = LoopBlend.blendFrames(rate, 8_000)
        // Live up to the join, byte for byte.
        assertThat(output(0, join)).isEqualTo(source(0, join))
        // Then the loop [10000, 18000): the captured bytes, outside the blend at the start of each repeat.
        val loopBytes = source(10_000 + blend, 8_000 - blend)
        assertThat(output(join + blend, 8_000 - blend)).isEqualTo(loopBytes)
        assertThat(output(join + 8_000 + blend, 8_000 - blend)).isEqualTo(loopBytes)
        assertThat(output(join + 16_000 + blend, 8_000 - blend)).isEqualTo(loopBytes)
        // The blend of the later repeats starts exactly on the audio after Out.
        assertThat(output(join + 8_000, 1)).isEqualTo(source(18_000, 1))
    }
}
