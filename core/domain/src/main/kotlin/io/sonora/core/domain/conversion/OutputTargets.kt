package io.sonora.core.domain.conversion

import io.sonora.core.domain.model.AudioFormat

/** Every generated id starts so; the XDJ-700 keeps its older `xdj-700`. */
private const val PREFIX = "out:"

/** A format's part of an id: its file extension. */
private fun tokenOf(format: AudioFormat): String = format.fileExtension

/** How an MP3 output spends its bits. */
sealed interface Mp3Mode {
    /** Constant bitrate, in kbps. */
    data class Cbr(val bitrateKbps: Int) : Mp3Mode {
        init {
            require(bitrateKbps in OutputTarget.Mp3.CBR_BITRATES_KBPS) { "Not an offered MP3 bitrate: $bitrateKbps kbps" }
        }
    }

    /** Variable bitrate at LAME quality `-V` [quality] (0 is the best). */
    data class Vbr(val quality: Int) : Mp3Mode {
        init {
            require(quality in OutputTarget.Mp3.VBR_QUALITIES) { "Not an offered MP3 VBR quality: V$quality" }
        }
    }
}

/**
 * An output the user picks in the converter: the XDJ-700 target, or a format with its settings. Each has a stable
 * [id], which is also the [ConversionProfile.id] [OutputTargets.profileFor] builds and what the history and the
 * preferences store. Settings outside the offered choices are refused, so every target round-trips through its id.
 */
sealed interface OutputTarget {
    val id: String
    val format: AudioFormat

    /** Pioneer XDJ-700: [ConversionProfiles.XDJ_700], unchanged (the CDJ export depends on its id and rules). */
    data object Xdj700 : OutputTarget {
        override val id: String get() = ConversionProfiles.XDJ_700.id
        override val format: AudioFormat get() = AudioFormat.AIFF
    }

    /**
     * Uncompressed or losslessly packed PCM (AIFF, WAV, FLAC) at [bitDepth] bits. Sources faster than
     * [sampleRateCapHz] are resampled to it; `null` keeps every source's rate.
     */
    data class Lossless(override val format: AudioFormat, val bitDepth: Int = 16, val sampleRateCapHz: Int? = null) : OutputTarget {
        init {
            require(format in FORMATS) { "Not a lossless output format: $format" }
            require(bitDepth in BIT_DEPTHS) { "Not an offered bit depth: $bitDepth" }
            require(sampleRateCapHz == null || sampleRateCapHz in SAMPLE_RATE_CAPS_HZ) { "Not an offered sample rate cap: $sampleRateCapHz" }
        }

        override val id: String get() = "$PREFIX${tokenOf(format)}:$bitDepth:${sampleRateCapHz ?: KEEP}"

        companion object {
            val FORMATS: List<AudioFormat> = listOf(AudioFormat.AIFF, AudioFormat.WAV, AudioFormat.FLAC)
            val BIT_DEPTHS: List<Int> = listOf(16, 24)
            val SAMPLE_RATE_CAPS_HZ: List<Int> = listOf(44_100, 48_000, 96_000)
            internal const val KEEP = "keep"
        }
    }

    /** AAC-LC in an M4A file at [bitrateKbps]. */
    data class Aac(val bitrateKbps: Int = DEFAULT_BITRATE_KBPS) : OutputTarget {
        init {
            require(bitrateKbps in BITRATES_KBPS) { "Not an offered AAC bitrate: $bitrateKbps kbps" }
        }

        override val id: String get() = "${PREFIX}aac:$bitrateKbps"
        override val format: AudioFormat get() = AudioFormat.AAC_M4A

        companion object {
            val BITRATES_KBPS: List<Int> = listOf(128, 192, 256, 320)
            const val DEFAULT_BITRATE_KBPS = 256
        }
    }

    /** Ogg Opus at [bitrateKbps], always 48 kHz. */
    data class Opus(val bitrateKbps: Int = DEFAULT_BITRATE_KBPS) : OutputTarget {
        init {
            require(bitrateKbps in BITRATES_KBPS) { "Not an offered Opus bitrate: $bitrateKbps kbps" }
        }

        override val id: String get() = "${PREFIX}opus:$bitrateKbps"
        override val format: AudioFormat get() = AudioFormat.OPUS

        companion object {
            val BITRATES_KBPS: List<Int> = listOf(96, 128, 160, 192, 256)
            const val DEFAULT_BITRATE_KBPS = 160
        }
    }

    /** MP3 at a constant or a variable bitrate. */
    data class Mp3(val mode: Mp3Mode = DEFAULT_CBR) : OutputTarget {
        override val id: String
            get() = when (mode) {
                is Mp3Mode.Cbr -> "${PREFIX}mp3:cbr:${mode.bitrateKbps}"
                is Mp3Mode.Vbr -> "${PREFIX}mp3:vbr:${mode.quality}"
            }
        override val format: AudioFormat get() = AudioFormat.MP3

        companion object {
            val CBR_BITRATES_KBPS: List<Int> = listOf(128, 192, 256, 320)
            val VBR_QUALITIES: List<Int> = listOf(0, 2, 4)
            val DEFAULT_CBR: Mp3Mode = Mp3Mode.Cbr(320)
            val DEFAULT_VBR: Mp3Mode = Mp3Mode.Vbr(0)
        }
    }

}

/** Builds the [ConversionProfile] of an [OutputTarget] and finds a target again by its id. */
object OutputTargets {

    /** The settings a format starts on when the user picks it. */
    fun defaultFor(format: AudioFormat): OutputTarget = when (format) {
        AudioFormat.AIFF, AudioFormat.WAV, AudioFormat.FLAC -> OutputTarget.Lossless(format)
        AudioFormat.AAC_M4A -> OutputTarget.Aac()
        AudioFormat.OPUS -> OutputTarget.Opus()
        AudioFormat.MP3 -> OutputTarget.Mp3()
    }

    /** The profile [target] converts with; its id is [OutputTarget.id]. */
    fun profileFor(target: OutputTarget): ConversionProfile = when (target) {
        OutputTarget.Xdj700 -> ConversionProfiles.XDJ_700
        is OutputTarget.Lossless -> lossless(target)
        is OutputTarget.Aac -> lossy(
            target, OutputContainer.M4A, OutputSampleFormat.AAC, SampleRateRule.upTo(48_000), "AAC ${target.bitrateKbps} kbps",
            bitrateBps = target.bitrateKbps * 1_000,
        )
        is OutputTarget.Opus -> lossy(
            target, OutputContainer.OGG_OPUS, OutputSampleFormat.OPUS, SampleRateRule.fixed(48_000),
            "Ogg Opus ${target.bitrateKbps} kbps, 48 kHz", bitrateBps = target.bitrateKbps * 1_000,
        )
        is OutputTarget.Mp3 -> when (val mode = target.mode) {
            is Mp3Mode.Cbr -> lossy(
                target, OutputContainer.MP3, OutputSampleFormat.MP3, SampleRateRule.upTo(48_000),
                "MP3 ${mode.bitrateKbps} kbps CBR", bitrateBps = mode.bitrateKbps * 1_000,
            )
            is Mp3Mode.Vbr -> lossy(
                target, OutputContainer.MP3, OutputSampleFormat.MP3, SampleRateRule.upTo(48_000),
                "MP3 VBR V${mode.quality}", vbrQuality = mode.quality,
            )
        }
    }

    /**
     * The target [id] names: `xdj-700`, or a generated id such as `out:flac:24:keep`, `out:wav:16:48000`,
     * `out:aac:256`, `out:opus:160`, `out:mp3:cbr:320` or `out:mp3:vbr:0`. `null` for anything else, including
     * settings no longer offered and profiles the user does not pick (the watch's).
     */
    fun parse(id: String): OutputTarget? {
        if (id == OutputTarget.Xdj700.id) return OutputTarget.Xdj700
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split(':')
        val target = try {
            when (parts.first()) {
                "aiff", "wav", "flac" -> parts.takeIf { it.size == 3 }?.let { (format, bits, rate) ->
                    val cap = if (rate == OutputTarget.Lossless.KEEP) null else rate.strictInt() ?: return null
                    OutputTarget.Lossless(AudioFormat.entries.single { tokenOf(it) == format }, bits.strictInt() ?: return null, cap)
                }
                "aac" -> parts.takeIf { it.size == 2 }?.let { OutputTarget.Aac(it[1].strictInt() ?: return null) }
                "opus" -> parts.takeIf { it.size == 2 }?.let { OutputTarget.Opus(it[1].strictInt() ?: return null) }
                "mp3" -> parts.takeIf { it.size == 3 }?.let { (_, mode, value) ->
                    val number = value.strictInt() ?: return null
                    when (mode) {
                        "cbr" -> OutputTarget.Mp3(Mp3Mode.Cbr(number))
                        "vbr" -> OutputTarget.Mp3(Mp3Mode.Vbr(number))
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            // Settings no longer (or never) offered.
            null
        }
        // Only the canonical spelling of an id names its target.
        return target?.takeIf { it.id == id }
    }

    private fun lossless(target: OutputTarget.Lossless): ConversionProfile {
        val (container, sampleFormat) = when (target.format) {
            AudioFormat.AIFF -> OutputContainer.AIFF to
                if (target.bitDepth == 24) OutputSampleFormat.PCM_24_BIG_ENDIAN else OutputSampleFormat.PCM_16_BIG_ENDIAN
            AudioFormat.WAV -> OutputContainer.WAV to
                if (target.bitDepth == 24) OutputSampleFormat.PCM_24_LITTLE_ENDIAN else OutputSampleFormat.PCM_16_LITTLE_ENDIAN
            else -> OutputContainer.FLAC to if (target.bitDepth == 24) OutputSampleFormat.FLAC_24 else OutputSampleFormat.FLAC_16
        }
        val cap = target.sampleRateCapHz
        val rate = if (cap == null) "source sample rate" else "up to ${formatKhz(cap)} kHz"
        return ConversionProfile(
            id = target.id,
            displayName = target.format.displayName,
            summary = "${target.format.displayName} ${target.bitDepth}-bit, $rate",
            container = container,
            sampleFormat = sampleFormat,
            sampleRate = if (cap == null) SampleRateRule.KEEP else SampleRateRule.upTo(cap),
            dither = DitherPolicy.TPDF_WHEN_REDUCING_PRECISION,
            channels = ChannelPolicy(maxChannels = 2),
            metadata = MetadataPolicy.COPY_TAGS_AND_ARTWORK,
        )
    }

    private fun lossy(
        target: OutputTarget,
        container: OutputContainer,
        sampleFormat: OutputSampleFormat,
        sampleRate: SampleRateRule,
        summary: String,
        bitrateBps: Int? = null,
        vbrQuality: Int? = null,
    ) = ConversionProfile(
        id = target.id,
        displayName = target.format.displayName,
        summary = summary,
        container = container,
        sampleFormat = sampleFormat,
        sampleRate = sampleRate,
        dither = DitherPolicy.NONE,
        channels = ChannelPolicy(maxChannels = 2),
        metadata = MetadataPolicy.COPY_TAGS_AND_ARTWORK,
        bitrateBps = bitrateBps,
        vbrQuality = vbrQuality,
    )

    /** "44.1", "48", "96". */
    private fun formatKhz(hz: Int): String = if (hz % 1_000 == 0) "${hz / 1_000}" else "${hz / 1_000}.${hz % 1_000 / 100}"

    /** Plain decimal digits only ("048", "+48" and " 48" are not ids we write). */
    private fun String.strictInt(): Int? = takeIf { it.isNotEmpty() && it.all(Char::isDigit) && !(length > 1 && it[0] == '0') }?.toIntOrNull()
}
