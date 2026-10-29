package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The decisions [AddToPlaylistHost] makes; the composable only renders them (no Compose UI test runner here). */
class AddToPlaylistHostTest {

    @Test
    fun `added songs confirm with the playlist name`() {
        assertThat(PlaylistEvent.Added("Road Trip").banner())
            .isEqualTo(PlaylistBanner(R.string.playlist_added_to, "Road Trip", success = true))
    }

    @Test
    fun `nothing added and failures are shown, never dropped`() {
        assertThat(PlaylistEvent.NothingToAdd.banner()).isEqualTo(PlaylistBanner(R.string.playlist_nothing_to_add, success = false))
        assertThat(PlaylistEvent.AddFailed.banner()).isEqualTo(PlaylistBanner(R.string.playlist_add_failed, success = false))
    }

    @Test
    fun `a playlist created elsewhere opens instead of showing a banner`() {
        assertThat(PlaylistEvent.Created(3).banner()).isNull()
    }

    @Test
    fun `the picker closes the flow unless New Playlist moved it on to naming`() {
        val flow = AddToPlaylistFlow()
        assertThat(flow.step).isEqualTo(AddToPlaylistStep.Picking)
        assertThat(flow.pickerDismissed()).isTrue()

        flow.newPlaylist()
        assertThat(flow.step).isEqualTo(AddToPlaylistStep.Naming)
        // The picker slides away after "New Playlist…"; that must not end the flow before the name is typed.
        assertThat(flow.pickerDismissed()).isFalse()
    }
}
