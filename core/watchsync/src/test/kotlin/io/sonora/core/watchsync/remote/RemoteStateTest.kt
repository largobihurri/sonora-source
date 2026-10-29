package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteStateTest {
    private val track = RemoteTrack(1, "Song", "Artist", "Album", durationMs = 200_000)
    private val playing = RemoteState(track = track, isPlaying = true, positionMs = 10_000)

    @Test
    fun `a playing song advances with the time since the state was taken`() {
        assertThat(playing.positionAt(elapsedMs = 2_500)).isEqualTo(12_500)
    }

    @Test
    fun `a paused song stays where it is`() {
        assertThat(playing.copy(isPlaying = false).positionAt(elapsedMs = 2_500)).isEqualTo(10_000)
    }

    @Test
    fun `the position never passes the end of the song or goes below zero`() {
        assertThat(playing.positionAt(elapsedMs = 900_000)).isEqualTo(200_000)
        assertThat(playing.positionAt(elapsedMs = -5_000_000)).isEqualTo(0)
    }

    @Test
    fun `without a song the position is zero`() {
        assertThat(RemoteState(isPlaying = true, positionMs = 5).positionAt(1_000)).isEqualTo(0)
    }

    @Test
    fun `the volume is a whole percent of its steps`() {
        assertThat(RemoteState(volume = 5, volumeMax = 15).volumePercent).isEqualTo(33)
        assertThat(RemoteState(volume = 15, volumeMax = 15).volumePercent).isEqualTo(100)
        assertThat(RemoteState(volume = 0, volumeMax = 0).volumePercent).isEqualTo(0)
        assertThat(RemoteState(volume = 99, volumeMax = 15).volumePercent).isEqualTo(100)
    }

    @Test
    fun `the volume after some steps stays inside the range`() {
        val state = RemoteState(volume = 14, volumeMax = 15)

        assertThat(state.withVolumeSteps(1).volume).isEqualTo(15)
        assertThat(state.withVolumeSteps(5).volume).isEqualTo(15)
        assertThat(state.withVolumeSteps(-20).volume).isEqualTo(0)
    }
}
