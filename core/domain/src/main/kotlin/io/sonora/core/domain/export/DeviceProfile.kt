package io.sonora.core.domain.export

import java.util.Locale

/** The file formats an export candidate can be in, as far as the device profiles tell them apart. */
enum class AudioContainer(val displayName: String) {
    /** Plain AIFF: big-endian integer PCM. */
    AIFF("AIFF"),

    /** AIFF-C, compressed or not (`sowt` little-endian PCM, `fl32` float, IMA ADPCM, ...). */
    AIFF_C("AIFF-C"),
    WAV("WAV"),
    FLAC("FLAC"),
    MP3("MP3"),
    M4A("M4A (AAC or ALAC)"),

    /** Raw AAC in ADTS frames (`.aac`). */
    AAC("AAC"),
    OGG("Ogg"),
    OPUS("Opus"),
    UNKNOWN("an unknown format"),
}

/**
 * What an export candidate holds, as far as a [DeviceProfile] needs to know: its [container] and, when the header
 * states them, the stored sample size, rate and channel count. `null` means not stated or not read (lossy formats).
 */
data class SourceAudio(
    val container: AudioContainer,
    val bitsPerSample: Int? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    /** Sample frames, when the header states them (AIFF). */
    val frameCount: Long? = null,
    /** The AIFF-C compression type (`sowt`, `fl32`, `ima4`, ...); `null` for every other container. */
    val compression: String? = null,
    /** The length, when known without decoding (from the stream's metadata), to estimate a conversion's size. */
    val durationMs: Long? = null,
)

/** Why a file cannot go to a device as it is; the export converts it on the way. */
sealed interface ProfileRejection {
    data class UnsupportedFormat(val found: AudioContainer) : ProfileRejection

    /** [bits] is `null` when the file does not state a sample size. */
    data class UnsupportedBitDepth(val bits: Int?) : ProfileRejection

    data class SampleRateTooHigh(val sampleRateHz: Int, val maximumHz: Int) : ProfileRejection

    data object UnknownSampleRate : ProfileRejection

    data class TooManyChannels(val channels: Int, val maximum: Int) : ProfileRejection
}

/**
 * A target player and the audio it is given: only files in one of the [acceptedContainers], stored with one of the
 * [acceptedBitDepths], at [maxSampleRateHz] or less and with at most [maxChannels] channels. The export converts
 * anything else that can be decoded (with the converter profile of the same id) to a temporary file it copies instead.
 * Adding a player is adding a profile.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    val acceptedContainers: Set<AudioContainer>,
    val acceptedBitDepths: Set<Int>,
    val maxSampleRateHz: Int,
    val maxChannels: Int,
) {
    /** What the profile accepts, for messages: "AIFF 16-bit up to 48 kHz". */
    val summary: String
        get() = acceptedContainers.joinToString("/") { it.displayName } + " " +
            acceptedBitDepths.sorted().joinToString("/") { "$it-bit" } + " up to ${khz(maxSampleRateHz)} kHz"

    /** Why [audio] cannot be exported as it is, or `null` when it can. The first failing rule wins, format first. */
    fun check(audio: SourceAudio): ProfileRejection? {
        val rate = audio.sampleRateHz
        val channels = audio.channelCount
        return when {
            audio.container !in acceptedContainers -> ProfileRejection.UnsupportedFormat(audio.container)
            audio.bitsPerSample !in acceptedBitDepths -> ProfileRejection.UnsupportedBitDepth(audio.bitsPerSample)
            rate == null -> ProfileRejection.UnknownSampleRate
            rate > maxSampleRateHz -> ProfileRejection.SampleRateTooHigh(rate, maxSampleRateHz)
            channels != null && channels > maxChannels -> ProfileRejection.TooManyChannels(channels, maxChannels)
            else -> null
        }
    }

    companion object {
        /**
         * Pioneer XDJ-700 (user decision, 2026-10-03): AIFF, 16-bit PCM, up to 48 kHz. The manuals list WAV/AIFF up to
         * 24-bit; 16-bit is a deliberately conservative choice. AIFF-C (`sowt` included) is not offered: the players
         * are only documented for plain AIFF.
         */
        val XDJ_700 = DeviceProfile(
            id = "xdj-700",
            displayName = "XDJ-700",
            acceptedContainers = setOf(AudioContainer.AIFF),
            acceptedBitDepths = setOf(16),
            maxSampleRateHz = 48_000,
            maxChannels = 2,
        )

        private fun khz(hz: Int): String =
            if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000.0)
    }
}
