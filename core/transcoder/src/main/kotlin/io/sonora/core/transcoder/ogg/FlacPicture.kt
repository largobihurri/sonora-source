package io.sonora.core.transcoder.ogg

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/**
 * The `METADATA_BLOCK_PICTURE` comment of Ogg Opus (RFC 7845 section 5.2): a FLAC `PICTURE` metadata block body
 * (RFC 9639 section 8.8, big-endian fields) of picture type 3 "front cover", base64-encoded without line breaks.
 */
object FlacPicture {

    const val COMMENT_NAME = "METADATA_BLOCK_PICTURE"
    private const val FRONT_COVER = 3
    private const val COLOUR_DEPTH = 24

    /** The block for the encoded image [data] (JPEG or PNG) of [width] x [height] pixels. */
    fun block(data: ByteArray, mimeType: String, width: Int, height: Int, description: String = ""): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        fun int(value: Int) = out.write(ByteBuffer.allocate(4).putInt(value).array())
        fun text(value: String) = value.toByteArray(Charsets.UTF_8).also { int(it.size); out.write(it) }
        int(FRONT_COVER)
        text(mimeType)
        text(description)
        int(width)
        int(height)
        int(COLOUR_DEPTH)
        int(0) // not an indexed-colour picture
        int(data.size)
        out.write(data)
        return out.toByteArray()
    }

    fun comment(data: ByteArray, mimeType: String, width: Int, height: Int): VorbisComment =
        VorbisComment(COMMENT_NAME, Base64.getEncoder().encodeToString(block(data, mimeType, width, height)))
}
