package io.sonora.core.data.library.info

import io.sonora.core.domain.audio.AiffHeader
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.library.TagText
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.EmbeddedArtworkInfo
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.TagEntry
import io.sonora.core.domain.model.TagInfo
import java.nio.ByteOrder
import kotlin.math.roundToLong
import org.jaudiotagger.audio.AudioHeader
import org.jaudiotagger.audio.mp3.MP3AudioHeader
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag

/** A JAudioTagger [AudioHeader]'s values, each read defensively (some readers throw for fields they lack). */
data class RawAudioHeader(
    val encodingType: String? = null,
    val format: String? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: String? = null,
    val bitrateKbps: Long? = null,
    val variableBitrate: Boolean = false,
    val lossless: Boolean? = null,
    val durationSec: Double? = null,
    /** The MP3 encoder tag (LAME / Xing info frame), e.g. `LAME3.100`. */
    val encoder: String? = null,
) {
    companion object {
        fun of(header: AudioHeader): RawAudioHeader = RawAudioHeader(
            encodingType = runCatching { header.encodingType }.getOrNull(),
            format = runCatching { header.format }.getOrNull(),
            sampleRate = runCatching { header.sampleRateAsNumber }.getOrNull(),
            bitsPerSample = runCatching { header.bitsPerSample }.getOrNull(),
            channels = runCatching { header.channels }.getOrNull(),
            bitrateKbps = runCatching { header.bitRateAsNumber }.getOrNull(),
            variableBitrate = runCatching { header.isVariableBitRate }.getOrDefault(false),
            lossless = runCatching { header.isLossless }.getOrNull(),
            durationSec = runCatching { header.preciseTrackLength }.getOrNull(),
            encoder = (header as? MP3AudioHeader)?.let { runCatching { it.encoder }.getOrNull() },
        )
    }
}

/** Turns what JAudioTagger (and, for AIFF, the shared [AiffHeader]) reports into the File Info model. */
object FileInfoMapper {

    /** [folderPath] below [libraryFolder] (both '/'-separated display paths); the whole path when not below it. */
    fun relativePath(folderPath: String?, libraryFolder: String?): String? {
        if (folderPath == null) return null
        if (libraryFolder.isNullOrEmpty()) return folderPath
        return when {
            folderPath == libraryFolder -> ""
            folderPath.startsWith("$libraryFolder/") -> folderPath.removePrefix("$libraryFolder/")
            else -> folderPath
        }
    }

    private val CONTAINERS = mapOf(
        "mp3" to "MP3",
        "flac" to "FLAC",
        "m4a" to "M4A (MPEG-4)",
        "alac" to "M4A (MPEG-4)",
        "mp4" to "M4A (MPEG-4)",
        "aac" to "AAC (ADTS)",
        "ogg" to "Ogg",
        "oga" to "Ogg",
        "opus" to "Ogg Opus",
        "wav" to "WAV",
        "aif" to "AIFF",
        "aiff" to "AIFF",
        "aifc" to "AIFF-C",
    )

    private val MIME_EXTENSIONS = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/flac" to "flac", "audio/x-flac" to "flac",
        "audio/mp4" to "m4a", "audio/m4a" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac",
        "audio/ogg" to "ogg", "audio/vorbis" to "ogg", "audio/opus" to "opus", "audio/wav" to "wav",
        "audio/wave" to "wav", "audio/x-wav" to "wav", "audio/vnd.wave" to "wav", "audio/aiff" to "aiff",
        "audio/x-aiff" to "aiff",
    )

    /** Container name from the extension, else the MIME type; [isAifc] (from the header) wins for AIFF files. */
    fun container(fileName: String, mimeType: String?, isAifc: Boolean = false): String? {
        val name = CONTAINERS[extension(fileName, mimeType)] ?: return null
        return if (isAifc && name == "AIFF") "AIFF-C" else name
    }

    /** Codec of a format JAudioTagger 3.0.1 cannot read (Opus, raw ADTS AAC). */
    fun codecWithoutReader(fileName: String, mimeType: String?): String? = when (extension(fileName, mimeType)) {
        "opus" -> "Opus"
        "aac" -> "AAC"
        else -> null
    }

    private fun extension(fileName: String, mimeType: String?): String? {
        val byName = fileName.substringAfterLast('.', "").lowercase()
        return byName.takeIf { it in CONTAINERS } ?: MIME_EXTENSIONS[mimeType?.lowercase()]
    }

    /**
     * Stream details from [raw] read with the JAudioTagger reader [readerKey]. For AIFF the [aiff] header (from the
     * shared parser) is authoritative: JAudioTagger misreports float AIFF-C as lossy and variable bitrate.
     */
    fun audio(raw: RawAudioHeader, readerKey: String, aiff: AiffHeader?): AudioStreamInfo {
        val encoding = TagText.clean(raw.encodingType)
        val bits = raw.bitsPerSample?.takeIf { it > 0 }
        val integer = bits?.let { SampleFormat.Integer(it) }
        val (codec, sampleFormat) = when (readerKey) {
            "mp3" -> (TagText.clean(raw.format)?.let { "MP3 ($it)" } ?: "MP3") to SampleFormat.Lossy
            "flac" -> "FLAC" to integer
            "ogg" -> "Vorbis" to SampleFormat.Lossy
            "m4a" -> {
                val text = "${encoding.orEmpty()} ${raw.format.orEmpty()}".lowercase()
                if ("alac" in text || "lossless" in text || raw.lossless == true) "ALAC" to integer else "AAC" to SampleFormat.Lossy
            }
            "wav" -> {
                val text = encoding.orEmpty().uppercase()
                when {
                    "FLOAT" in text -> "PCM" to bits?.let { SampleFormat.FloatingPoint(it) }
                    "PCM" in text -> "PCM" to integer
                    else -> encoding to if (raw.lossless == false) SampleFormat.Lossy else integer
                }
            }
            else -> encoding to if (raw.lossless == false) SampleFormat.Lossy else integer
        }
        val base = AudioStreamInfo(
            codec = codec,
            encoder = raw.encoder?.let(::encoderName),
            sampleRateHz = raw.sampleRate?.takeIf { it > 0 },
            sampleFormat = sampleFormat,
            channelCount = channelCount(raw.channels),
            bitrateKbps = raw.bitrateKbps?.takeIf { it > 0 }?.toInt(),
            variableBitrate = raw.variableBitrate && sampleFormat == SampleFormat.Lossy,
            durationMs = raw.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() },
        )
        return if (aiff == null) base else fromAiff(base, aiff)
    }

    private fun fromAiff(base: AudioStreamInfo, header: AiffHeader): AudioStreamInfo {
        val layout = header.layout
        val littleEndian = layout.byteOrder == ByteOrder.LITTLE_ENDIAN && layout.bitsPerSample > 8
        return base.copy(
            codec = if (littleEndian) "PCM (little-endian)" else "PCM",
            sampleRateHz = header.sampleRateHz,
            sampleFormat = when (layout.kind) {
                PcmLayout.SampleKind.FLOAT -> SampleFormat.FloatingPoint(layout.bitsPerSample)
                else -> SampleFormat.Integer(header.declaredBits)
            },
            channelCount = header.channelCount,
            bitrateKbps = (header.sampleRate * header.bytesPerFrame * 8 / 1000).roundToLong().toInt(),
            variableBitrate = false,
            durationMs = header.durationUs / 1000,
        )
    }

    /** `LAME3.100` -> `LAME 3.100`; other names as they are. */
    private fun encoderName(raw: String): String? {
        val name = TagText.clean(raw) ?: return null
        return ENCODER_VERSION.matchEntire(name)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: name
    }

    private val ENCODER_VERSION = Regex("^([A-Za-z]+)(\\d.*)$")

    /** Headers report a count ("2") or an MP3 channel mode ("Joint Stereo", "Dual mono", "Mono"). */
    private fun channelCount(value: String?): Int? {
        val text = TagText.clean(value)?.lowercase() ?: return null
        text.toIntOrNull()?.let { return it.takeIf { count -> count > 0 } }
        return when {
            "dual" in text || "stereo" in text -> 2
            "mono" in text -> 1
            else -> null
        }
    }

    /** The encoder named in [tag]: a FLAC/Vorbis vendor string, else the ENCODER field. */
    fun tagEncoder(tag: Tag): String? {
        val vendor = when (tag) {
            is FlacTag -> tag.vorbisCommentTag?.vendor
            is VorbisCommentTag -> tag.vendor
            else -> null
        }
        return TagText.clean(vendor) ?: tag.text(FieldKey.ENCODER)
    }

    /**
     * Common fields, ReplayGain, the first embedded picture ([imageSize] decodes its dimensions) and every other
     * non-empty text field of [tag]. Blank values are left out.
     */
    fun tags(tag: Tag, imageSize: (ByteArray) -> Pair<Int, Int>?): TagInfo {
        val fields = rawFields(tag)
        return TagInfo(
            title = tag.text(FieldKey.TITLE),
            artist = tag.text(FieldKey.ARTIST),
            albumArtist = tag.text(FieldKey.ALBUM_ARTIST),
            album = tag.text(FieldKey.ALBUM),
            discNumber = tag.number(FieldKey.DISC_NO),
            discTotal = tag.number(FieldKey.DISC_TOTAL),
            trackNumber = tag.number(FieldKey.TRACK),
            trackTotal = tag.number(FieldKey.TRACK_TOTAL),
            date = tag.text(FieldKey.YEAR),
            genre = tag.text(FieldKey.GENRE),
            composer = tag.text(FieldKey.COMPOSER),
            comment = tag.text(FieldKey.COMMENT),
            replayGain = fields.filter { it.key.uppercase().startsWith(REPLAYGAIN_PREFIX) },
            artwork = artwork(tag, imageSize),
            otherFields = fields.filter { it.key.uppercase().let { key -> !key.startsWith(REPLAYGAIN_PREFIX) && key !in SHOWN_KEYS } },
        )
    }

    private fun artwork(tag: Tag, imageSize: (ByteArray) -> Pair<Int, Int>?): EmbeddedArtworkInfo? {
        val art = runCatching { tag.firstArtwork }.getOrNull() ?: return null
        val bytes = art.binaryData?.takeIf { it.isNotEmpty() } ?: return null
        val size = runCatching { imageSize(bytes) }.getOrNull()
            ?: art.width.takeIf { it > 0 }?.let { width -> art.height.takeIf { it > 0 }?.let { width to it } }
        return EmbeddedArtworkInfo(size?.first, size?.second, bytes.size.toLong(), TagText.clean(art.mimeType))
    }

    /** Text fields as stored: ID3 user frames by their description, MP4 freeform atoms by their name. */
    private fun rawFields(tag: Tag): List<TagEntry> {
        val entries = mutableListOf<TagEntry>()
        val fields = runCatching { tag.fields }.getOrNull() ?: return entries
        while (fields.hasNext()) {
            val field = fields.next()
            entry(field)?.let(entries::add)
        }
        return entries.distinct()
    }

    private fun entry(field: TagField): TagEntry? {
        if (runCatching { field.isBinary }.getOrDefault(true) || field !is TagTextField) return null
        val value = TagText.clean(runCatching { field.content }.getOrNull()) ?: return null
        val key = when {
            field is AbstractID3v2Frame && field.body is FrameBodyTXXX -> (field.body as FrameBodyTXXX).description
            field is Mp4TagReverseDnsField -> field.descriptor
            else -> field.id
        }
        return TagText.clean(key)?.let { TagEntry(it, value) }
    }

    private fun Tag.text(key: FieldKey): String? = TagText.clean(runCatching { getFirst(key) }.getOrNull())

    private fun Tag.number(key: FieldKey): Int? = text(key)?.let { LEADING_NUMBER.find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 }

    private val LEADING_NUMBER = Regex("^\\d+")

    private const val REPLAYGAIN_PREFIX = "REPLAYGAIN_"

    /**
     * Field ids (upper case) already shown as common fields, artwork or the encoder (the Vorbis vendor string), in
     * ID3v2.2-2.4, Vorbis comments, MP4 and RIFF INFO.
     */
    private val SHOWN_KEYS = setOf(
        // ID3v2.3 / 2.4
        "TIT2", "TPE1", "TPE2", "TALB", "TPOS", "TRCK", "TYER", "TDRC", "TCON", "TCOM", "COMM", "APIC",
        // ID3v2.2
        "TT2", "TP1", "TP2", "TAL", "TPA", "TRK", "TYE", "TCO", "TCM", "COM", "PIC",
        // Vorbis comments
        "TITLE", "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "ALBUM", "DISCNUMBER", "DISCTOTAL",
        "TOTALDISCS", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DATE", "YEAR", "GENRE", "COMPOSER", "COMMENT",
        "DESCRIPTION", "METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME", "VENDOR",
        // MP4
        "©NAM", "©ART", "AART", "©ALB", "DISK", "TRKN", "©DAY", "©GEN", "GNRE", "©WRT", "©CMT", "COVR",
        // RIFF INFO
        "INAM", "IART", "IPRD", "ICRD", "IGNR", "ICMT", "ITRK", "IPRT", "IMUS",
    )
}
