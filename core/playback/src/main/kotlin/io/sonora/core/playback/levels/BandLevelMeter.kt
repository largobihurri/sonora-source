package io.sonora.core.playback.levels

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import io.sonora.core.domain.playback.LevelBand
import io.sonora.core.domain.playback.PlaybackBandLevels
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures the level of each [LevelBand] in the PCM the player hands its audio sink, and publishes one frame of
 * levels to [levels] every [HOP_SECONDS] of audio, stamped with the media time it ends at.
 *
 * - **Signal**: the channels are mixed to mono (their mean), then split by a bank of RBJ biquads: per band a
 *   Linkwitz-Riley (two Butterworth sections) high-pass at its low edge and low-pass at its high edge, except the
 *   outer edges, a single Butterworth section (see [LevelBand]). That is 14 sections per mono sample.
 * - **Level**: each band's mean square runs through a one-pole [RMS_SECONDS] average (RMS, not peak, so loud masters
 *   do not sit pinned at the top), read once per frame as dBFS on the AES-17 scale (a full-scale sine is 0 dB).
 * - **Ballistics**: the shown level rises towards it with a [ATTACK_SECONDS] time constant (on power) and falls
 *   back at [RELEASE_DB_PER_SECOND], so the bars jump with each hit and settle back smoothly.
 * - **Formats**: the encodings the equalizer handles (8, 16, 24 and 32-bit integer in either byte order, float), any
 *   channel count and rate. The buffer is read in place (absolute reads), never consumed or copied.
 * - **Cost**: it measures every buffer the sink plays, watched or not, so audio already queued when a viewer arrives
 *   has its levels (the AudioTrack can hold seconds of it). About 130 floating-point operations per sample,
 *   allocation-free on the playback thread. The meter restarts only when the stream does ([configure], [flush]).
 */
@UnstableApi
class BandLevelMeter(private val levels: LiveBandLevels) {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    // Filter bank: per section b0, b1, b2, a1, a2 (normalised), and the two transposed direct form II states.
    private val coefficients = DoubleArray(SECTIONS * 5)
    private val state = DoubleArray(SECTIONS * 2)
    private val meanSquares = DoubleArray(BANDS)
    private val shown = FloatArray(BANDS)
    private var rmsCoefficient = 0.0
    private var attack = 0.0
    private var releaseDb = 0f
    private var hopFrames = 0
    private var framesInHop = 0

    /** A new stream format; a gapless track in the same format keeps the filters running. */
    fun configure(sampleRate: Int, channels: Int, encoding: Int) {
        if (sampleRate == this.sampleRate && channels == this.channels && encoding == this.encoding) return
        this.sampleRate = sampleRate
        this.channels = channels
        this.encoding = encoding
        bytesPerSample = bytesPerSample(encoding)
        if (bytesPerSample > 0 && sampleRate > 0 && channels > 0) design()
        restart()
    }

    /** A seek, or a new stream: the old audio's levels and filter state are dropped. */
    fun flush() {
        restart()
    }

    /** The sink is playing out media time [positionUs] now (the renderer asks it often while playing). */
    fun played(positionUs: Long) = levels.reportPlayed(positionUs)

    /** The sink started or stopped playing out. */
    fun running(running: Boolean) = levels.setRunning(running)

    /** Measures [buffer] (from its position to its limit, left untouched), which starts at [presentationTimeUs]. */
    fun process(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (bytesPerSample == 0 || sampleRate == 0 || channels == 0) return
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        val start = buffer.position()
        val mix = 1.0 / channels
        for (frame in 0 until frames) {
            var p = start + frame * frameBytes
            var x = 0.0
            for (ch in 0 until channels) {
                x += sample(buffer, p)
                p += bytesPerSample
            }
            x *= mix
            for (band in 0 until BANDS) {
                var y = x
                for (section in FIRST_SECTION[band] until FIRST_SECTION[band + 1]) {
                    val c = section * 5
                    val s = section * 2
                    val out = coefficients[c] * y + state[s]
                    state[s] = coefficients[c + 1] * y - coefficients[c + 3] * out + state[s + 1]
                    state[s + 1] = coefficients[c + 2] * y - coefficients[c + 4] * out
                    y = out
                }
                meanSquares[band] += rmsCoefficient * (y * y - meanSquares[band])
            }
            if (++framesInHop == hopFrames) {
                framesInHop = 0
                publish(presentationTimeUs + (frame + 1) * 1_000_000L / sampleRate)
            }
        }
    }

    private fun publish(endUs: Long) {
        for (band in 0 until BANDS) {
            val power = 2 * meanSquares[band]
            val db = toDb(power)
            val now = shown[band]
            shown[band] = if (db > now) {
                // The attack runs on power, so a hit out of silence shows at once whatever the floor.
                val shownPower = 10.0.pow(now / 10.0)
                toDb(shownPower + (power - shownPower) * attack)
            } else {
                maxOf(db, now - releaseDb)
            }
        }
        levels.publish(endUs, shown)
    }

    private fun toDb(power: Double): Float = (10 * log10(power + 1e-30)).toFloat().coerceAtLeast(FLOOR_DB)

    private fun restart() {
        state.fill(0.0)
        meanSquares.fill(0.0)
        shown.fill(FLOOR_DB)
        framesInHop = 0
        levels.clearFrames()
    }

    private fun design() {
        hopFrames = (sampleRate * HOP_SECONDS).roundToInt().coerceAtLeast(1)
        val hop = hopFrames.toDouble() / sampleRate
        rmsCoefficient = 1 - exp(-1.0 / (RMS_SECONDS * sampleRate))
        attack = 1 - exp(-hop / ATTACK_SECONDS)
        releaseDb = (RELEASE_DB_PER_SECOND * hop).toFloat()
        var section = 0
        for (band in LevelBand.entries) {
            repeat(if (band == LevelBand.entries.first()) 1 else 2) { design(section++, band.lowHz, highPass = true) }
            repeat(if (band == LevelBand.entries.last()) 1 else 2) { design(section++, band.highHz, highPass = false) }
        }
    }

    /** A Butterworth (Q = 1/√2) high or low-pass section from the RBJ cookbook, its corner kept below Nyquist. */
    private fun design(section: Int, frequencyHz: Double, highPass: Boolean) {
        val f = minOf(frequencyHz, MAX_CORNER_FRACTION * sampleRate)
        val w0 = 2 * PI * f / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
        val a0 = 1 + alpha
        val b1 = if (highPass) -(1 + cosW) else 1 - cosW
        val b0 = (if (highPass) 1 + cosW else 1 - cosW) / 2
        val c = section * 5
        coefficients[c] = b0 / a0
        coefficients[c + 1] = b1 / a0
        coefficients[c + 2] = b0 / a0
        coefficients[c + 3] = -2 * cosW / a0
        coefficients[c + 4] = (1 - alpha) / a0
    }

    private fun sample(b: ByteBuffer, p: Int): Double = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((b.get(p).toInt() and 0xFF) - 128) / 128.0
        C.ENCODING_PCM_16BIT -> ((b.get(p + 1).toInt() shl 8) or u(b, p)) / 32_768.0
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> ((b.get(p).toInt() shl 8) or u(b, p + 1)) / 32_768.0
        C.ENCODING_PCM_24BIT -> ((b.get(p + 2).toInt() shl 16) or (u(b, p + 1) shl 8) or u(b, p)) / 8_388_608.0
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> ((b.get(p).toInt() shl 16) or (u(b, p + 1) shl 8) or u(b, p + 2)) / 8_388_608.0
        C.ENCODING_PCM_32BIT -> int32(b, p, bigEndian = false) / 2_147_483_648.0
        C.ENCODING_PCM_32BIT_BIG_ENDIAN -> int32(b, p, bigEndian = true) / 2_147_483_648.0
        C.ENCODING_PCM_FLOAT -> Float.fromBits(int32(b, p, bigEndian = false)).toDouble()
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> Float.fromBits(int32(b, p, bigEndian = true)).toDouble()
        else -> 0.0
    }

    private fun u(b: ByteBuffer, index: Int): Int = b.get(index).toInt() and 0xFF

    private fun int32(b: ByteBuffer, p: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            (b.get(p).toInt() shl 24) or (u(b, p + 1) shl 16) or (u(b, p + 2) shl 8) or u(b, p + 3)
        } else {
            (b.get(p + 3).toInt() shl 24) or (u(b, p + 2) shl 16) or (u(b, p + 1) shl 8) or u(b, p)
        }

    companion object {
        /** Audio per level frame: about one display frame at 60 Hz. */
        const val HOP_SECONDS = LiveBandLevels.HOP_SECONDS

        /** Time constant of each band's running mean square: long enough to smooth a 20 Hz wave's ripple to a dB or two. */
        const val RMS_SECONDS = 0.01

        /** Time constant of a rising level (VU-like fast attack). */
        const val ATTACK_SECONDS = 0.01

        /** How fast a level falls back: the bars' 48 dB range in 0.3 s. */
        const val RELEASE_DB_PER_SECOND = 160.0

        /** The quietest level reported (digital silence). */
        const val FLOOR_DB = -96f

        private val BANDS = PlaybackBandLevels.BAND_COUNT

        /** Where each band's sections start: bass 3 (one high-pass, two low-pass), kick and mids 4, highs 3. */
        private val FIRST_SECTION = intArrayOf(0, 3, 7, 11, 14)
        private const val SECTIONS = 14

        private const val BUTTERWORTH_Q = 0.7071067811865476
        private const val MAX_CORNER_FRACTION = 0.45

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> 4
            else -> 0
        }
    }
}
