package io.sonora.core.domain.export

import io.sonora.core.domain.audio.AiffException
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Tells what an export candidate is from its first bytes, falling back to its name and MIME type: AIFF and AIFF-C
 * headers are read with the shared [AiffParser] (sample size, rate, channels, compression), other formats are only
 * named, since no profile accepts them yet. A file called `.aiff` without an AIFF header is not taken for one.
 *
 * Reads only the header: for AIFF up to the start of the sound data, skipping other chunks.
 */
object SourceAudioProbe {
    private const val SIGNATURE_BYTES = 12
    private const val AIFF_C_FORM = "AIFC"

    /**
     * What [open] (a fresh stream of the file named [fileName], of type [mimeType]) holds. An AIFF header the parser
     * refuses (compressed AIFF-C, an exotic sample size or rate) still reports what it found, so the profile can say
     * why it does not fit.
     *
     * @throws IOException when the file cannot be read, or is an AIFF too damaged to tell.
     */
    fun probe(fileName: String, mimeType: String?, open: () -> InputStream): SourceAudio =
        BufferedInputStream(open()).use { stream ->
            stream.mark(SIGNATURE_BYTES)
            val head = stream.readNBytes(SIGNATURE_BYTES)
            stream.reset()
            if (AiffParser.isAiff(head)) {
                aiff(stream, isAifc = String(head, 8, 4, Charsets.US_ASCII) == AIFF_C_FORM)
            } else {
                SourceAudio(bySignature(head) ?: byName(fileName, mimeType))
            }
        }

    private fun aiff(stream: InputStream, isAifc: Boolean): SourceAudio {
        val form = if (isAifc) AudioContainer.AIFF_C else AudioContainer.AIFF
        return try {
            val header = AiffParser.parse(AiffSource.of(stream))
            SourceAudio(
                container = if (header.isAifc) AudioContainer.AIFF_C else AudioContainer.AIFF,
                bitsPerSample = header.declaredBits,
                sampleRateHz = header.sampleRateHz,
                channelCount = header.channelCount,
                frameCount = header.frameCount,
                compression = header.compressionType.takeIf { header.isAifc },
            )
        } catch (e: AiffException.UnsupportedCompression) {
            SourceAudio(AudioContainer.AIFF_C, compression = e.type)
        } catch (e: AiffException.UnsupportedSampleSize) {
            SourceAudio(form, bitsPerSample = e.bits, compression = e.compressionType.takeIf { isAifc })
        } catch (e: AiffException.UnsupportedSampleRate) {
            SourceAudio(form, sampleRateHz = Math.round(e.rate).toInt())
        } catch (e: AiffException.UnsupportedChannelCount) {
            SourceAudio(form, channelCount = e.channels)
        }
    }

    private fun bySignature(head: ByteArray): AudioContainer? {
        fun text(at: Int, length: Int) =
            if (head.size >= at + length) String(head, at, length, Charsets.ISO_8859_1) else ""
        val first = head.getOrNull(0)?.toInt()?.and(0xFF)
        val second = head.getOrNull(1)?.toInt()?.and(0xFF)
        return when {
            text(0, 4) == "fLaC" -> AudioContainer.FLAC
            text(0, 4) == "RIFF" && text(8, 4) == "WAVE" -> AudioContainer.WAV
            text(0, 4) == "OggS" -> AudioContainer.OGG
            text(4, 4) == "ftyp" -> AudioContainer.M4A
            text(0, 3) == "ID3" -> AudioContainer.MP3
            // ADTS (raw AAC): a 12-bit sync and layer bits 00, which MPEG audio reserves.
            first == 0xFF && second != null && second and 0xF6 == 0xF0 -> AudioContainer.AAC
            // MPEG audio frame: an 11-bit sync and a layer (I, II or III) other than 00.
            first == 0xFF && second != null && second and 0xE0 == 0xE0 && second and 0x06 != 0 -> AudioContainer.MP3
            else -> null
        }
    }

    private fun byName(fileName: String, mimeType: String?): AudioContainer {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return EXTENSIONS[extension] ?: MIME_TYPES[mimeType?.lowercase(Locale.ROOT)] ?: AudioContainer.UNKNOWN
    }

    private val EXTENSIONS = mapOf(
        "wav" to AudioContainer.WAV,
        "wave" to AudioContainer.WAV,
        "flac" to AudioContainer.FLAC,
        "mp3" to AudioContainer.MP3,
        "m4a" to AudioContainer.M4A,
        "mp4" to AudioContainer.M4A,
        "aac" to AudioContainer.AAC,
        "alac" to AudioContainer.M4A,
        "ogg" to AudioContainer.OGG,
        "oga" to AudioContainer.OGG,
        "opus" to AudioContainer.OPUS,
    )

    private val MIME_TYPES = mapOf(
        "audio/wav" to AudioContainer.WAV,
        "audio/x-wav" to AudioContainer.WAV,
        "audio/flac" to AudioContainer.FLAC,
        "audio/x-flac" to AudioContainer.FLAC,
        "audio/mpeg" to AudioContainer.MP3,
        "audio/mp4" to AudioContainer.M4A,
        "audio/aac" to AudioContainer.AAC,
        "audio/ogg" to AudioContainer.OGG,
        "audio/opus" to AudioContainer.OPUS,
    )
}
