package io.sonora.core.domain.export

/**
 * The cover pictures of a device library: where a track's picture comes from when its tags hold none, and the two
 * JPEGs the players read for it.
 */
interface ExportArtwork {

    /**
     * The picture the library shows for the audio document [documentUri] apart from its tags (the image of its folder),
     * or `null` when there is none or the library does not index that document. Never throws except for cancellation.
     */
    suspend fun libraryPicture(documentUri: String): ByteArray?

    /**
     * [picture] (any image format the platform decodes) as the players' two JPEGs, cropped square from its centre;
     * `null` when it does not decode. Never throws. Blocking.
     */
    fun deviceImages(picture: ByteArray): DeviceArtworkImages?

    companion object {
        /** No pictures at all: every track goes without a cover. */
        val NONE: ExportArtwork = object : ExportArtwork {
            override suspend fun libraryPicture(documentUri: String): ByteArray? = null

            override fun deviceImages(picture: ByteArray): DeviceArtworkImages? = null
        }
    }
}

/** A cover as the players read it: [small], 80x80, and [large], 240x240, both JPEG. Equal when their bytes are. */
class DeviceArtworkImages(val small: ByteArray, val large: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is DeviceArtworkImages && small.contentEquals(other.small) && large.contentEquals(other.large)

    override fun hashCode(): Int = small.contentHashCode() * 31 + large.contentHashCode()

    override fun toString(): String = "DeviceArtworkImages(${small.size} + ${large.size} bytes)"
}
