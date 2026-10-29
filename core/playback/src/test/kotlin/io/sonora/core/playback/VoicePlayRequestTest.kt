package io.sonora.core.playback

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePlayRequestTest {

    private fun request(query: String?) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        query?.let { putExtra(SearchManager.QUERY, it) }
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, "Joni Mitchell")
    }

    @Test
    fun `only the assistant's play-from-search intent is a voice request`() {
        assertThat(VoicePlayRequest.handles(request("blue"))).isTrue()
        assertThat(VoicePlayRequest.handles(Intent(Intent.ACTION_MAIN))).isFalse()
        assertThat(VoicePlayRequest.handles(null)).isFalse()
    }

    @Test
    fun `the request reaches the session as the car's voice item, query and focus included`() {
        val item = VoicePlayRequest.mediaItem(request("joni mitchell"))

        assertThat(item.requestMetadata.searchQuery).isEqualTo("joni mitchell")
        assertThat(CarVoice.request(item.requestMetadata.searchQuery!!, item.requestMetadata.extras).artist)
            .isEqualTo("Joni Mitchell")
        assertThat(item.localConfiguration).isNull()
    }

    @Test
    fun `a request without a query asks for anything`() {
        assertThat(VoicePlayRequest.mediaItem(request(null)).requestMetadata.searchQuery).isEqualTo("")
    }
}
