package io.sonora.core.domain.model

/** Everything known about one track's file, read on demand for the File Info sheet. `null` = unknown. */
data class TrackFileInfo(
    val location: FileLocation,
    val file: FileDetails,
    val audio: AudioStreamInfo,
    /** The file's tags; `null` when they could not be read (no tag reader for the format, or a damaged tag). */
    val tags: TagInfo?,
)

data class FileLocation(
    /** The library folder holding the file, as shown in Settings (e.g. `Music/Rock`). */
    val libraryFolder: String?,
    /** Directories between the library folder and the file, '/'-separated; empty when it sits at the top. */
    val relativePath: String?,
    val fileName: String,
    /** The document's content URI. */
    val contentUri: String,
)

data class FileDetails(
    /** Container name, e.g. `FLAC`, `AIFF-C`, `M4A (MPEG-4)`. */
    val container: String?,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
)

/** How samples are stored: integer PCM of some depth, floating point, or a lossy codec without a bit depth. */
sealed interface SampleFormat {
    data class Integer(val bits: Int) : SampleFormat

    data class FloatingPoint(val bits: Int) : SampleFormat

    data object Lossy : SampleFormat
}

data class AudioStreamInfo(
    /** Codec name, e.g. `PCM`, `FLAC`, `MP3 (MPEG-1 Layer III)`, `AAC`, `ALAC`. */
    val codec: String?,
    /** Encoder that wrote the stream, e.g. `LAME 3.100`. */
    val encoder: String?,
    val sampleRateHz: Int?,
    val sampleFormat: SampleFormat?,
    val channelCount: Int?,
    val bitrateKbps: Int?,
    val variableBitrate: Boolean,
    val durationMs: Long?,
)

/** One tag field as stored, e.g. `REPLAYGAIN_TRACK_GAIN: -6.20 dB`. */
data class TagEntry(val key: String, val value: String)

data class EmbeddedArtworkInfo(val width: Int?, val height: Int?, val sizeBytes: Long, val mimeType: String?)

data class TagInfo(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    /** As tagged: a year or a full date. */
    val date: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    /** ReplayGain fields (track/album gain and peak), keys as tagged. */
    val replayGain: List<TagEntry> = emptyList(),
    val artwork: EmbeddedArtworkInfo? = null,
    /** Every other non-empty text field, in tag order. */
    val otherFields: List<TagEntry> = emptyList(),
)

/** What the player is actually doing with the current track's audio. */
data class DecodedAudioFormat(
    /** The queue entry ([QueueItem.id]) this format was reported for. */
    val queueItemId: Long,
    /** MIME type of the stream the decoder (or the PCM path) receives, e.g. `audio/flac`, `audio/raw`. */
    val inputMimeType: String?,
    /** PCM encoding of the input, for uncompressed sources. */
    val inputEncoding: PcmEncoding?,
    val inputSampleRateHz: Int?,
    val channelCount: Int?,
    /** PCM encoding the system AudioTrack was opened with. */
    val outputEncoding: PcmEncoding?,
    val outputSampleRateHz: Int?,
)

enum class PcmEncoding(val bits: Int, val isFloat: Boolean, val isBigEndian: Boolean = false) {
    PCM_8(8, false),
    PCM_16(16, false),
    PCM_16_BIG_ENDIAN(16, false, isBigEndian = true),
    PCM_24(24, false),
    PCM_24_BIG_ENDIAN(24, false, isBigEndian = true),
    PCM_32(32, false),
    PCM_32_BIG_ENDIAN(32, false, isBigEndian = true),
    FLOAT_32(32, true),
    FLOAT_32_BIG_ENDIAN(32, true, isBigEndian = true),
    FLOAT_64(64, true),
    FLOAT_64_BIG_ENDIAN(64, true, isBigEndian = true),
}
