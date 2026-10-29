package io.sonora.core.playback

import android.os.Bundle
import android.provider.MediaStore
import io.sonora.core.domain.car.CarVoiceFocus
import io.sonora.core.domain.car.CarVoiceRequest

/** Android Auto's "play X on Sonora": the query plus the media focus and fields the assistant recognised. */
internal object CarVoice {
    fun request(query: String, extras: Bundle?): CarVoiceRequest = CarVoiceRequest(
        query = query,
        focus = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> CarVoiceFocus.ARTIST
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> CarVoiceFocus.ALBUM
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> CarVoiceFocus.SONG
            else -> null
        },
        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.takeIf { it.isNotBlank() },
        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.takeIf { it.isNotBlank() },
        title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() },
    )
}
