package io.sonora.core.domain.conversion

/**
 * File container a profile writes. Adding one means adding an encoder for it in the transcoder; [isLossy] says
 * whether what it holds (as Sonora writes it) loses information.
 */
enum class OutputContainer(val fileExtension: String, val mimeType: String, val isLossy: Boolean) {
    AIFF("aiff", "audio/aiff", isLossy = false),

    /** RIFF WAVE with integer PCM. */
    WAV("wav", "audio/wav", isLossy = false),
    FLAC("flac", "audio/flac", isLossy = false),

    /** AAC in an MPEG-4 file. */
    M4A("m4a", "audio/mp4", isLossy = true),

    /** Opus packets in an Ogg stream (RFC 7845), Vorbis comments for tags: what the Wear OS app plays. */
    OGG_OPUS("opus", "audio/ogg", isLossy = true),

    /** MPEG-1/2 Layer III. */
    MP3("mp3", "audio/mpeg", isLossy = true),
}

/** How the output stores its samples. */
enum class OutputSampleFormat(val bitsPerSample: Int) {
    /** Signed 16-bit integers, most significant byte first (AIFF's native order). */
    PCM_16_BIG_ENDIAN(16),

    /** Signed 24-bit integers, most significant byte first (AIFF). */
    PCM_24_BIG_ENDIAN(24),

    /** Signed 16-bit integers, least significant byte first (WAV's native order). */
    PCM_16_LITTLE_ENDIAN(16),

    /** Signed 24-bit integers, least significant byte first (WAV). */
    PCM_24_LITTLE_ENDIAN(24),

    /** FLAC frames of 16-bit samples. */
    FLAC_16(16),

    /** FLAC frames of 24-bit samples. */
    FLAC_24(24),

    /** Opus packets from an encoder fed 16-bit PCM; the quantiser's width is the only precision that matters. */
    OPUS(16),

    /** AAC-LC frames from an encoder fed 16-bit PCM. */
    AAC(16),

    /** MP3 frames from an encoder fed 16-bit PCM. */
    MP3(16),
}

/**
 * Which output sample rate a source gets: rates up to [maxSampleRateHz] are kept as they are, anything faster is
 * resampled to [resampleTargetHz]. A [fixed] rule outputs [resampleTargetHz] whatever the source (Opus only takes
 * 8, 12, 16, 24 and 48 kHz).
 */
data class SampleRateRule(val maxSampleRateHz: Int, val resampleTargetHz: Int, val alwaysResample: Boolean = false) {
    init {
        require(resampleTargetHz in 1..maxSampleRateHz) {
            "Resample target $resampleTargetHz Hz must lie in 1..$maxSampleRateHz Hz"
        }
    }

    fun targetFor(sourceRateHz: Int): Int =
        if (!alwaysResample && sourceRateHz <= maxSampleRateHz) sourceRateHz else resampleTargetHz

    companion object {
        /** Every source ends up at exactly [rateHz]. */
        fun fixed(rateHz: Int): SampleRateRule = SampleRateRule(rateHz, rateHz, alwaysResample = true)

        /** Sources up to [rateHz] keep their rate; faster ones are resampled to [rateHz]. */
        fun upTo(rateHz: Int): SampleRateRule = SampleRateRule(rateHz, rateHz)

        /** Every source keeps its rate. */
        val KEEP: SampleRateRule = upTo(Int.MAX_VALUE)
    }
}

/** Whether samples are dithered when the output holds less precision than the signal. */
enum class DitherPolicy {
    /** Triangular (TPDF, ±1 LSB) dither whenever the signal is not already on the output's integer grid. */
    TPDF_WHEN_REDUCING_PRECISION,

    /** Plain rounding. */
    NONE,
}

/** Sources with up to [maxChannels] channels keep them; sources with more are downmixed to [maxChannels]. */
data class ChannelPolicy(val maxChannels: Int) {
    init {
        require(maxChannels in 1..2) { "Downmix targets are mono or stereo, not $maxChannels channels" }
    }

    fun targetFor(sourceChannels: Int): Int = minOf(sourceChannels, maxChannels)
}

/** What happens to the source's tags and artwork. Implemented by the metadata stage (T6). */
enum class MetadataPolicy {
    COPY_TAGS_AND_ARTWORK,
    NONE,
}

/**
 * A conversion target as data: everything the converter needs to know to turn any decodable source into files a
 * given device or use accepts. Adding a target (MP3 for the phone, FLAC, CDJ-3000) means a new profile here plus,
 * for a new [container] or [sampleFormat], a new encoder; the planning and the engine stay unchanged.
 */
data class ConversionProfile(
    /** Stable identifier for preferences and history; never shown. */
    val id: String,
    val displayName: String,
    /** One line for the profile picker, e.g. "AIFF 16-bit, up to 48 kHz". */
    val summary: String,
    val container: OutputContainer,
    val sampleFormat: OutputSampleFormat,
    val sampleRate: SampleRateRule,
    val dither: DitherPolicy,
    val channels: ChannelPolicy,
    val metadata: MetadataPolicy,
    /** Target bitrate in bits per second for lossy containers; `null` for lossless ones and variable-quality MP3. */
    val bitrateBps: Int? = null,
    /** MP3 variable-bitrate quality (LAME `-V`, 0 best to 9 smallest); `null` for every other output. */
    val vbrQuality: Int? = null,
) {
    init {
        require(bitrateBps == null || bitrateBps > 0) { "Bitrate must be positive: $bitrateBps" }
        require(vbrQuality == null || vbrQuality in 0..9) { "VBR quality must lie in 0..9: $vbrQuality" }
    }

    val fileExtension: String get() = container.fileExtension

    /** Whether the output loses information (AAC, Opus, MP3), whatever the source was. */
    val isLossy: Boolean get() = container.isLossy

    /** The output's display name for a source called [sourceDisplayName]: its extension replaced by ours. */
    fun outputFileName(sourceDisplayName: String): String {
        val dot = sourceDisplayName.lastIndexOf('.')
        val stem = if (dot > 0) sourceDisplayName.substring(0, dot) else sourceDisplayName
        return "$stem.$fileExtension"
    }
}

/**
 * The registered profiles. The converter UI lists [all], even while it holds a single entry; the outputs a user sets
 * up (format plus settings) are [OutputTargets], found here by their ids too.
 */
object ConversionProfiles {

    /** Pioneer XDJ-700: plays AIFF 16-bit PCM up to 48 kHz from USB. */
    val XDJ_700: ConversionProfile = ConversionProfile(
        id = "xdj-700",
        displayName = "Pioneer XDJ-700",
        summary = "AIFF 16-bit, up to 48 kHz",
        container = OutputContainer.AIFF,
        sampleFormat = OutputSampleFormat.PCM_16_BIG_ENDIAN,
        sampleRate = SampleRateRule(maxSampleRateHz = 48_000, resampleTargetHz = 44_100),
        dither = DitherPolicy.TPDF_WHEN_REDUCING_PRECISION,
        channels = ChannelPolicy(maxChannels = 2),
        metadata = MetadataPolicy.COPY_TAGS_AND_ARTWORK,
    )

    /**
     * The Wear OS watch: Ogg Opus at 192 kbps variable bitrate, always 48 kHz (the watch's Bluetooth link runs at
     * 48 kHz, so nothing resamples there). Not in [all]: the converter's picker lists only profiles a user chooses;
     * the watch transfer picks this one itself.
     */
    val WATCH_OPUS: ConversionProfile = ConversionProfile(
        id = "watch-opus",
        displayName = "Wear OS watch (Opus)",
        summary = "Ogg Opus 192 kbps, 48 kHz",
        container = OutputContainer.OGG_OPUS,
        sampleFormat = OutputSampleFormat.OPUS,
        sampleRate = SampleRateRule.fixed(48_000),
        dither = DitherPolicy.NONE,
        channels = ChannelPolicy(maxChannels = 2),
        metadata = MetadataPolicy.COPY_TAGS_AND_ARTWORK,
        bitrateBps = 192_000,
    )

    /** The profiles the converter offers. */
    val all: List<ConversionProfile> = listOf(XDJ_700)

    val DEFAULT: ConversionProfile get() = XDJ_700

    /** Any registered profile, listed or not, or any [OutputTargets] id (history stores ids); `null` when unknown. */
    fun byId(id: String): ConversionProfile? =
        (all + WATCH_OPUS).firstOrNull { it.id == id } ?: OutputTargets.parse(id)?.let(OutputTargets::profileFor)
}
