package io.sonora.core.transcoder.wav

import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The body of a WAV file's `LIST` chunk of type `INFO`: the basic fields players without ID3 support show (title,
 * artist, album, genre, date, track, comment, copyright) plus `ISFT` naming the converter. Values are UTF-8 (what
 * current taggers write), NUL-terminated, each sub-chunk padded to an even size. The complete tags and the cover go in
 * the file's `id3 ` chunk.
 */
object RiffInfo {

    /** Id of the chunk that holds the [body]. */
    const val CHUNK_ID = "LIST"

    fun body(metadata: TrackMetadata, encoder: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("INFO".toByteArray(Charsets.US_ASCII))
        fun add(id: String, value: String?) {
            if (value.isNullOrBlank()) return
            val text = value.toByteArray(Charsets.UTF_8) + 0.toByte()
            out.write(id.toByteArray(Charsets.US_ASCII))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(text.size).array())
            out.write(text)
            if (text.size and 1 == 1) out.write(0)
        }
        add("INAM", metadata.text[MetadataTextField.TITLE])
        add("IART", metadata.text[MetadataTextField.ARTIST])
        add("IPRD", metadata.text[MetadataTextField.ALBUM])
        add("IGNR", metadata.text[MetadataTextField.GENRE])
        add("ICRD", metadata.date)
        add("ITRK", metadata.trackNumber?.toString())
        add("ICMT", metadata.comment)
        add("ICOP", metadata.text[MetadataTextField.COPYRIGHT])
        add("ISFT", encoder)
        return out.toByteArray()
    }
}
