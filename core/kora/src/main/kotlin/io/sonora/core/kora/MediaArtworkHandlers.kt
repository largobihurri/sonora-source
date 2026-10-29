package io.sonora.core.kora

import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * `media.artwork` over the covers Sonora already shows.
 *
 * - The key is an album key (`a:<albumId>`, see [trackSummary]); the cover is the one the library gives that album
 *   (`Track.artworkUri`, the same the UI draws). No path or URI is ever received or sent.
 * - The cover is decoded off the main thread, sampled down while decoding, never upscaled, and answered as a JPEG of at
 *   most [MediaArtwork.MAX_ARTWORK_BYTES] (quality ~85, lowered and then the size, see [ArtworkSizing]).
 * - An album the library does not have, one without artwork, or a cover that cannot be decoded answers
 *   `invalid_payload` (the caller shows no artwork). The message never echoes the key.
 *
 * The library is taken lazily, as in the other handlers.
 */
internal class MediaArtworkHandlers(
    private val library: () -> LibraryRepository,
    private val covers: CoverSource,
) {
    @Inject
    constructor(library: dagger.Lazy<LibraryRepository>, covers: AndroidCoverSource) : this({ library.get() }, covers)

    /** Registers every operation of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaArtwork>) = with(builder) {
        on(MediaArtwork.GetArtwork) { request -> getArtwork(request) }
    }

    suspend fun getArtwork(request: ArtworkRequest): ArtworkImage {
        val albumId = albumIdOfArtwork(request.artworkId)
        val uri = library().tracks().first().firstOrNull { it.albumId == albumId && it.artworkUri != null }?.artworkUri
            ?: throw invalidPayload("No artwork for that id")
        return withContext(Dispatchers.IO) {
            val cover = covers.open(uri, request.maxEdgePx) ?: throw invalidPayload("The artwork cannot be read")
            val encoded = ArtworkSizing.encodeUnderCap(cover, request.maxEdgePx, MediaArtwork.MAX_ARTWORK_BYTES)
            ArtworkImage(encoded.bytes, ArtworkMimeType.JPEG, encoded.widthPx, encoded.heightPx)
        }
    }
}
