package io.sonora.core.cast.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.MediaMetadata as CastMetadata
import com.google.common.truth.Truth.assertThat
import io.sonora.core.cast.server.CastQueueUrls
import io.sonora.core.cast.server.CastToken
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queue entries as the phone's player holds them, turned into what the receiver is sent, and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CastMediaItemConverterTest {

    private val token = CastToken("0123456789abcdef0123456789abcdef")
    private var urls: CastQueueUrls? = CastQueueUrls("192.168.1.20", 41_234, token, now = { 0L })
    private val converter = CastMediaItemConverter { urls }

    private val songUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FSong.flac"

    private fun entry(mimeType: String? = null, artwork: String? = "file:///data/art/7.jpg") = MediaItem.Builder()
        .setMediaId("12:3")
        .setUri(songUri)
        .setMimeType(mimeType)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").setArtist("Artist").setArtworkUri(artwork?.toUri()).build())
        .build()

    @Test
    fun `the receiver gets the server's URL, the entry's id and a cast type`() {
        val info = converter.toMediaQueueItem(entry()).media!!

        assertThat(info.contentId).isEqualTo("12:3")
        assertThat(info.contentUrl).isEqualTo("http://192.168.1.20:41234/${token.value}/audio/1")
        assertThat(info.contentType).isEqualTo("audio/flac")
        assertThat(info.metadata!!.getString(CastMetadata.KEY_TITLE)).isEqualTo("Song")
        assertThat(info.metadata!!.images.single().url.toString()).isEqualTo("http://192.168.1.20:41234/${token.value}/art/1")
    }

    @Test
    fun `the library type wins over the file name`() {
        assertThat(converter.toMediaQueueItem(entry(mimeType = "audio/x-wav")).media!!.contentType).isEqualTo("audio/wav")
    }

    @Test
    fun `an entry without artwork is sent without an image`() {
        assertThat(converter.toMediaQueueItem(entry(artwork = null)).media!!.metadata!!.images).isEmpty()
    }

    @Test
    fun `the server serves exactly the files of the sent entries`() {
        converter.toMediaQueueItem(entry())

        assertThat(urls!!.resolve("/${token.value}/audio/1").toString()).contains(songUri)
        assertThat(urls!!.resolve("/${token.value}/art/1").toString()).contains("file:///data/art/7.jpg")
    }

    @Test
    fun `an entry read back from the receiver plays its file again on the phone`() {
        val back = converter.toMediaItem(converter.toMediaQueueItem(entry()))

        assertThat(back.mediaId).isEqualTo("12:3")
        assertThat(back.localConfiguration!!.uri.toString()).isEqualTo(songUri)
        assertThat(back.requestMetadata.mediaUri.toString()).isEqualTo(songUri)
    }

    @Test
    fun `without a server session the entry keeps its own URI instead of failing`() {
        urls = null

        assertThat(converter.toMediaQueueItem(entry()).media!!.contentUrl).isEqualTo(songUri)
    }
}
