package io.sonora.player.wear.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** The watch's player: music audio attributes with audio focus, and no sound out of the watch's own speaker. */
@OptIn(UnstableApi::class)
object WearPlayer {
    fun build(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when the headphones go away (or the link drops) instead of playing on through the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Playback waits (suppression reason UNSUITABLE_AUDIO_OUTPUT) while the watch has no suitable output such as
            // Bluetooth headphones; the screens then ask for them, and playback goes on by itself once they are there.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            // Songs are local files: keep the CPU awake while playing, not the Wi-Fi.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
}
