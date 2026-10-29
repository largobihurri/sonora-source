package io.sonora.core.domain.playback

/**
 * The four frequency bands the playback level meter splits the music into, in bar order. Each band is a
 * fourth-order (Linkwitz-Riley) band-pass between [lowHz] and [highHz]: neighbours cross at -6 dB and fall 24 dB per
 * octave past a shared edge (a 50 Hz tone reads about 20 dB lower in [KICK] than in [BASS]). The outer edges
 * ([BASS]'s 20 Hz and [HIGHS]' 16 kHz) are gentler second-order slopes: they only keep rumble and ultrasonics out.
 *
 * - [BASS]: sub and bass notes (20-90 Hz).
 * - [KICK]: the body and punch of the kick drum and bass guitar (90-250 Hz).
 * - [MIDS]: voices, guitars, keys, snare (250 Hz-4 kHz).
 * - [HIGHS]: cymbals, hats, air (4-16 kHz).
 */
enum class LevelBand(val lowHz: Double, val highHz: Double) {
    BASS(20.0, 90.0),
    KICK(90.0, 250.0),
    MIDS(250.0, 4_000.0),
    HIGHS(4_000.0, 16_000.0),
}

/**
 * The level of each [LevelBand] in what is being heard right now, measured from the player's own PCM (no microphone,
 * no permission). Levels are RMS in dBFS on the AES-17 scale (a full-scale sine reads 0 dB) with VU-like ballistics.
 *
 * The player measures the audio it plays whether anyone looks or not (so a viewer that arrives finds the levels of the
 * audio already queued). A viewer holds a [watch] handle while it shows the levels and closes it when it stops; with no
 * handle open [read] gives nothing. [read] is cheap and allocation-free, meant to be called from the draw phase on
 * every frame.
 */
interface PlaybackBandLevels {

    /** Lets [read] hand out levels until the returned handle is closed (reference counted across viewers). */
    fun watch(): AutoCloseable

    /**
     * Writes the level of each band (in [LevelBand] order, dBFS) heard now into [into] and returns true; returns false
     * when there are no live levels (nobody watching yet, nothing playing through the meter, a path the meter cannot
     * hear, or levels gone stale), leaving [into] unchanged.
     */
    fun read(into: FloatArray): Boolean

    companion object {
        /** Number of bands, the size of the array [read] fills. */
        val BAND_COUNT: Int = LevelBand.entries.size
    }
}
