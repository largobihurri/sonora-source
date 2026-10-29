package io.sonora.core.domain.playback

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** Which ReplayGain value playback applies. */
enum class ReplayGainMode {
    /** None: every file plays at its own level (the default). */
    OFF,

    /** Each song's own gain: every song at the same loudness. */
    TRACK,

    /** The album's gain: an album keeps its loud and quiet songs, at the same loudness as other albums. */
    ALBUM,

    /** The album gain while the queue plays an album in order, the track gain otherwise. */
    AUTO,
}

/**
 * The volume normalisation settings: the [mode] and a pre-amp added to every tagged gain, kept within
 * [MIN_PREAMP_DB]..[MAX_PREAMP_DB] (a value that is not a number reads as 0 dB).
 */
class ReplayGainSettings(val mode: ReplayGainMode = ReplayGainMode.OFF, preampDb: Float = 0f) {
    val preampDb: Float = if (preampDb.isNaN()) 0f else preampDb.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)

    fun copy(mode: ReplayGainMode = this.mode, preampDb: Float = this.preampDb) = ReplayGainSettings(mode, preampDb)

    override fun equals(other: Any?): Boolean = other is ReplayGainSettings && other.mode == mode && other.preampDb == preampDb

    override fun hashCode(): Int = 31 * mode.hashCode() + preampDb.hashCode()

    override fun toString(): String = "ReplayGainSettings(mode=$mode, preampDb=$preampDb)"

    companion object {
        const val MIN_PREAMP_DB = -6f
        const val MAX_PREAMP_DB = 6f
    }
}

/**
 * A file's ReplayGain tags: gains in dB, peaks as linear amplitudes (1.0 is full scale); `null` when absent or
 * unreadable.
 */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    /** Whether the file carries no usable gain (peaks alone change nothing). */
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    companion object {
        val NONE = ReplayGainTags()

        private const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
        private const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
        private const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
        private const val ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

        /** Gains beyond this many dB either way are not real ReplayGain values. */
        private const val MAX_GAIN_DB = 64f
        private const val MAX_PEAK = 100f

        private val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")

        /**
         * The tags among tag [fields] (key to value, as stored: ID3 `TXXX` descriptions, Vorbis comments, MP4 `----`
         * names). Keys match in any case; the first valid value of a repeated field wins; other fields are ignored.
         */
        fun fromFields(fields: Iterable<Pair<String, String>>): ReplayGainTags {
            var tags = NONE
            for ((key, value) in fields) {
                tags = when (key.trim().uppercase()) {
                    TRACK_GAIN -> if (tags.trackGainDb == null) tags.copy(trackGainDb = parseGain(value)) else tags
                    TRACK_PEAK -> if (tags.trackPeak == null) tags.copy(trackPeak = parsePeak(value)) else tags
                    ALBUM_GAIN -> if (tags.albumGainDb == null) tags.copy(albumGainDb = parseGain(value)) else tags
                    ALBUM_PEAK -> if (tags.albumPeak == null) tags.copy(albumPeak = parsePeak(value)) else tags
                    else -> tags
                }
            }
            return tags
        }

        /** A gain as tagged ("-6.20 dB", "+3.1dB", "-7", a decimal comma too), or `null` when it is not one. */
        fun parseGain(value: String): Float? {
            val number = value.trim().removeSuffix("dB").removeSuffix("db").removeSuffix("DB").removeSuffix("Db").trim()
            return parseNumber(number)?.takeIf { it in -MAX_GAIN_DB..MAX_GAIN_DB }
        }

        /** A peak as tagged ("0.988312"), or `null` unless it is a positive amplitude. */
        fun parsePeak(value: String): Float? = parseNumber(value.trim())?.takeIf { it > 0f && it <= MAX_PEAK }

        private fun parseNumber(text: String): Float? {
            val decimal = if ('.' !in text) text.replace(',', '.') else text
            if (!NUMBER.matches(decimal)) return null
            return decimal.toFloatOrNull()?.takeIf { it.isFinite() }
        }
    }
}

/** Where a queued song sits in its album: the album's identity (blank when unknown), its disc and track numbers. */
data class AlbumPosition(val album: String, val disc: Int?, val track: Int?)

/** Decibels and linear amplitude. */
object Decibels {
    fun toLinear(db: Double): Double = if (db == 0.0) 1.0 else 10.0.pow(db / 20)

    fun fromLinear(linear: Double): Double = 20 * log10(linear)
}

/** How playback turns ReplayGain tags and the settings into a gain. */
object ReplayGain {

    /** The peak assumed for a file with no peak tag: full scale. */
    private const val FULL_SCALE = 1f

    /** The mode that applies: [ReplayGainMode.AUTO] picks the album gain only while an album plays in order. */
    fun resolveMode(mode: ReplayGainMode, albumInOrder: Boolean): ReplayGainMode = when (mode) {
        ReplayGainMode.AUTO -> if (albumInOrder) ReplayGainMode.ALBUM else ReplayGainMode.TRACK
        else -> mode
    }

    /**
     * The linear gain to play a file with [tags] at: its track or album gain (the other one when the chosen one is
     * missing) plus the pre-amp, capped by the matching peak so that peak × gain stays at or under full scale. A
     * missing peak counts as full scale, so such a file is never boosted past unity (it may be playing with no
     * limiter behind it). Unity when off, and for untagged files (the pre-amp only moves tagged files towards the
     * ReplayGain reference).
     */
    fun gain(tags: ReplayGainTags, settings: ReplayGainSettings, albumInOrder: Boolean): Double {
        val album = when (resolveMode(settings.mode, albumInOrder)) {
            ReplayGainMode.OFF -> return 1.0
            ReplayGainMode.ALBUM -> tags.albumGainDb != null || tags.trackGainDb == null
            else -> tags.trackGainDb == null
        }
        val gainDb = (if (album) tags.albumGainDb else tags.trackGainDb) ?: return 1.0
        val peak = (if (album) tags.albumPeak else tags.trackPeak) ?: FULL_SCALE
        val linear = Decibels.toLinear(gainDb.toDouble() + settings.preampDb)
        return min(linear, 1.0 / peak)
    }

    /**
     * Whether [current] plays as part of an album in order: the song before it in the queue is the one right before
     * it on its album, or the song after it is the one right after. Needs known, non-blank albums and track numbers;
     * a song with no disc number counts as disc 1.
     */
    fun albumInOrder(previous: AlbumPosition?, current: AlbumPosition, next: AlbumPosition?): Boolean =
        (previous != null && follows(previous, current)) || (next != null && follows(current, next))

    private fun follows(first: AlbumPosition, second: AlbumPosition): Boolean {
        if (first.album.isBlank() || first.album != second.album) return false
        val firstTrack = first.track ?: return false
        val secondTrack = second.track ?: return false
        val firstDisc = first.disc ?: 1
        val secondDisc = second.disc ?: 1
        return (secondDisc == firstDisc && secondTrack == firstTrack + 1) || (secondDisc == firstDisc + 1 && secondTrack == 1)
    }
}
