package io.sonora.core.rekordbox.timing

import io.sonora.core.rekordbox.pdb.DeviceFileType

/**
 * Moves a time from Sonora's analysis time base into rekordbox's, for grids and cues exported to the players (D5).
 *
 * The two differ only for lossy files, at the start of the audio:
 *
 * - Sonora analyses what the player plays (D1.26): the Android decoder drops the MP3 decoder's own delay of
 *   [MP3_DECODER_DELAY_FRAMES] frames (the platform MP3 codec does it for every file, and Media3 plays through the same
 *   codec), then the reader drops the encoder delay the container states (`encoder-delay`: the LAME/Xing header or an
 *   iTunSMPB comment for MP3; the edit list or iTunSMPB for AAC in MP4), as Media3's player does. FFmpeg, which the
 *   D1.20 calibration decoded with, drops the same frames, except that it keeps the 529 of an MP3 without a LAME
 *   header (hence D1.20's 0 ms for those files, where the app is 12 ms early).
 * - rekordbox drops nothing: its time 0 is the first sample its decoder outputs. Measured in D1.20 (waveform
 *   cross-correlation of the FFmpeg-decoded analysis against PWV5/PWV3, ours minus rekordbox): mp3 -26.4 ms median
 *   over 28 files (0 for files without a LAME header; 576 + 529 frames at 44.1 kHz is 25.1 ms), m4a -48.5 ms (one
 *   file; 2112 frames of AAC priming is 47.9 ms), wav -0.5, aiff 0.0, flac 0.0.
 *
 * So rekordbox's time is the analysis time plus the frames Sonora dropped: the encoder delay (what the decoder
 * trimmed, 0 when the file states none) plus, for MP3 only, [MP3_DECODER_DELAY_FRAMES]. Everything else is already
 * in rekordbox's time base. The end of the file (encoder padding) does not move any time.
 *
 * Assumption to check on the phone (D1.24): the device's MP3 decoder is the platform software one, which drops 529
 * frames; a vendor decoder that keeps them would move MP3 analyses 12 ms later and need a 0 here.
 */
object RekordboxTimeBase {

    /** The MPEG-1 Layer III decoder delay (529 frames: 528 of the synthesis filterbank plus one). */
    const val MP3_DECODER_DELAY_FRAMES: Int = 529

    /**
     * How much later the same sound is in rekordbox's time base than in the analysis, in ms.
     *
     * @param format the file's format as the players number it.
     * @param encoderDelayFrames frames the decoder dropped from the start for the encoder delay (MP3) or priming (AAC):
     *   the container's `encoder-delay`, 0 when it states none. Must be 0 for WAV, AIFF and FLAC (no evidence how
     *   rekordbox would treat a delay there, so it is refused rather than guessed).
     * @param sampleRateHz the file's sample rate.
     * @throws IllegalArgumentException for a negative delay, a delay on a lossless format or a rate below 1.
     */
    fun offsetMs(format: DeviceFileType, encoderDelayFrames: Int, sampleRateHz: Int): Double {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(encoderDelayFrames >= 0) { "encoderDelayFrames must not be negative: $encoderDelayFrames" }
        val decoderDelay = when (format) {
            DeviceFileType.MP3 -> MP3_DECODER_DELAY_FRAMES
            DeviceFileType.M4A -> 0
            DeviceFileType.FLAC, DeviceFileType.WAV, DeviceFileType.AIFF -> {
                require(encoderDelayFrames == 0) { "$format has no encoder delay, got $encoderDelayFrames frames" }
                0
            }
        }
        return (decoderDelay + encoderDelayFrames) * MS_PER_SECOND / sampleRateHz
    }

    /** [analysisMs] (a beat, a cue, a loop end) in rekordbox's time base; see [offsetMs] for the other parameters. */
    fun toRekordboxMs(analysisMs: Double, format: DeviceFileType, encoderDelayFrames: Int, sampleRateHz: Int): Double {
        require(analysisMs.isFinite()) { "analysisMs must be finite: $analysisMs" }
        return analysisMs + offsetMs(format, encoderDelayFrames, sampleRateHz)
    }

    /** The inverse of [toRekordboxMs], for times read from a rekordbox export. */
    fun toAnalysisMs(rekordboxMs: Double, format: DeviceFileType, encoderDelayFrames: Int, sampleRateHz: Int): Double {
        require(rekordboxMs.isFinite()) { "rekordboxMs must be finite: $rekordboxMs" }
        return rekordboxMs - offsetMs(format, encoderDelayFrames, sampleRateHz)
    }

    private const val MS_PER_SECOND = 1_000.0
}
