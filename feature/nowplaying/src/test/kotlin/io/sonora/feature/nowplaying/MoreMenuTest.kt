package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import org.junit.Test

class MoreMenuTest {

    private val track = Track(
        id = 7L,
        uri = "content://media/7",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        durationMs = 1_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        albumId = 1L,
        artworkUri = null,
        mimeType = "audio/flac",
        sizeBytes = 1L,
    )

    private fun labels(sections: List<List<MoreMenuEntry>>) = sections.map { section -> section.map { it.label } }

    @Test
    fun `the menu offers add to playlist and share song, then the playback section with sleep timer, speed and pitch, a-b loop and DJ mode`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}))

        assertThat(labels(sections)).containsExactly(
            listOf(R.string.now_playing_add_to_playlist, R.string.now_playing_share),
            listOf(
                R.string.sleep_timer_title,
                R.string.speed_pitch_title,
                R.string.now_playing_ab_loop,
                R.string.now_playing_dj_mode,
                R.string.now_playing_dub_mode,
            ),
        ).inOrder()
    }

    @Test
    fun `a build without DUB mode has no DUB mode row`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, dubOffered = false))

        assertThat(sections.flatten().map { it.label }).doesNotContain(R.string.now_playing_dub_mode)
    }

    @Test
    fun `the DUB mode row comes after DJ mode, is checked while on and flips the mode`() {
        var flipped = 0
        val off = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onDubMode = { flipped++ }))
        val on = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, dubMode = true))
        val offRow = off[1].single { it.label == R.string.now_playing_dub_mode }
        assertThat(offRow.checked).isFalse()
        assertThat(on[1].single { it.label == R.string.now_playing_dub_mode }.checked).isTrue()
        offRow.onClick()
        assertThat(flipped).isEqualTo(1)
        // The DJ row is as it was: unchecked, its own action.
        assertThat(on[1].single { it.label == R.string.now_playing_dj_mode }.checked).isFalse()
    }

    @Test
    fun `in DUB mode the A-B loop row is left out and speed and pitch stays`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, dubMode = true))
        assertThat(sections[1].map { it.label }).containsExactly(
            R.string.sleep_timer_title,
            R.string.speed_pitch_title,
            R.string.now_playing_dj_mode,
            R.string.now_playing_dub_mode,
        ).inOrder()
    }

    @Test
    fun `a-b loop toggles the mode and is checked while on`() {
        var toggled = false
        val off = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onAbLoop = { toggled = true }))
        val on = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, abLoop = true))

        val entry = off.flatten().single { it.label == R.string.now_playing_ab_loop }
        assertThat(entry.checked).isFalse()
        entry.onClick()

        assertThat(toggled).isTrue()
        assertThat(on.flatten().single { it.label == R.string.now_playing_ab_loop }.checked).isTrue()
    }

    @Test
    fun `DJ mode toggles the mode and is checked while on`() {
        var toggled = false
        val off = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onDjMode = { toggled = true }))
        val on = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true))

        val entry = off.flatten().single { it.label == R.string.now_playing_dj_mode }
        assertThat(entry.checked).isFalse()
        assertThat(entry.enabled).isTrue()
        entry.onClick()

        assertThat(toggled).isTrue()
        assertThat(on.flatten().single { it.label == R.string.now_playing_dj_mode }.checked).isTrue()
    }

    @Test
    fun `in DJ mode the playback section drops speed and pitch and the a-b loop`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true))

        assertThat(labels(sections)).containsExactly(
            listOf(R.string.now_playing_add_to_playlist, R.string.now_playing_share),
            listOf(R.string.sleep_timer_title, R.string.now_playing_dj_mode, R.string.now_playing_dub_mode),
        ).inOrder()
    }

    @Test
    fun `DJ mode can be switched with nothing playing`() {
        val sections = moreMenuSections(null, MoreMenuActions(onAddToPlaylist = {}))

        assertThat(sections.flatten().single { it.label == R.string.now_playing_dj_mode }.enabled).isTrue()
    }

    @Test
    fun `sleep timer opens its panel`() {
        var opened = false
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onSleepTimer = { opened = true }))

        sections.flatten().single { it.label == R.string.sleep_timer_title }.onClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun `speed and pitch opens its panel`() {
        var opened = false
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onSpeedPitch = { opened = true }))

        sections.flatten().single { it.label == R.string.speed_pitch_title }.onClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun `add to playlist hands over the song playing`() {
        var added: Track? = null
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = { added = it }))

        val entry = sections.flatten().single { it.label == R.string.now_playing_add_to_playlist }
        assertThat(entry.enabled).isTrue()
        entry.onClick()

        assertThat(added).isEqualTo(track)
    }

    @Test
    fun `with nothing playing add to playlist is disabled`() {
        val sections = moreMenuSections(null, MoreMenuActions(onAddToPlaylist = {}))

        assertThat(sections.flatten().single { it.label == R.string.now_playing_add_to_playlist }.enabled).isFalse()
    }

    @Test
    fun `share song shares the song playing`() {
        val shared = mutableListOf<Track>()
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, onShare = { shared += it }))

        val share = sections.flatten().single { it.label == R.string.now_playing_share }
        assertThat(share.enabled).isTrue()
        share.onClick()
        assertThat(shared).containsExactly(track)
    }

    @Test
    fun `share song is dimmed with nothing playing`() {
        val sections = moreMenuSections(null, MoreMenuActions(onAddToPlaylist = {}, onShare = {}))

        assertThat(sections.flatten().single { it.label == R.string.now_playing_share }.enabled).isFalse()
    }

    @Test
    fun `in DJ mode an editor row follows the DJ mode row`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true, onEditGrid = {}, gridEditable = true))

        assertThat(labels(sections)).containsExactly(
            listOf(R.string.now_playing_add_to_playlist, R.string.now_playing_share),
            listOf(R.string.sleep_timer_title, R.string.now_playing_dj_mode, R.string.now_playing_edit_beat_grid, R.string.now_playing_dub_mode),
        ).inOrder()
    }

    @Test
    fun `the editor row opens the editor when the track has an analysis`() {
        var opened = false
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true, onEditGrid = { opened = true }, gridEditable = true))

        val entry = sections.flatten().single { it.label == R.string.now_playing_edit_beat_grid }
        assertThat(entry.enabled).isTrue()
        assertThat(entry.checked).isNull()
        entry.onClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun `the editor row is dimmed until the track has an analysis`() {
        val sections = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true, onEditGrid = {}, gridEditable = false))

        assertThat(sections.flatten().single { it.label == R.string.now_playing_edit_beat_grid }.enabled).isFalse()
    }

    @Test
    fun `there is no editor row out of DJ mode or where there is no editor`() {
        val off = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = false, onEditGrid = {}, gridEditable = true))
        val elsewhere = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true, onEditGrid = null, gridEditable = true))

        assertThat(off.flatten().map { it.label }).doesNotContain(R.string.now_playing_edit_beat_grid)
        assertThat(elsewhere.flatten().map { it.label }).doesNotContain(R.string.now_playing_edit_beat_grid)
    }

    @Test
    fun `in DJ mode an analyse again row follows the editor row`() {
        val sections = moreMenuSections(
            track,
            MoreMenuActions(onAddToPlaylist = {}, djMode = true, onEditGrid = {}, gridEditable = true, onReanalyse = {}, canReanalyse = true),
        )

        assertThat(labels(sections)).containsExactly(
            listOf(R.string.now_playing_add_to_playlist, R.string.now_playing_share),
            listOf(
                R.string.sleep_timer_title,
                R.string.now_playing_dj_mode,
                R.string.now_playing_edit_beat_grid,
                R.string.now_playing_reanalyse,
                R.string.now_playing_dub_mode,
            ),
        ).inOrder()
    }

    @Test
    fun `the analyse again row asks for a new analysis when the track has settled`() {
        var asked = false
        val sections = moreMenuSections(
            track,
            MoreMenuActions(onAddToPlaylist = {}, djMode = true, onReanalyse = { asked = true }, canReanalyse = true),
        )

        val entry = sections.flatten().single { it.label == R.string.now_playing_reanalyse }
        assertThat(entry.enabled).isTrue()
        assertThat(entry.checked).isNull()
        entry.onClick()

        assertThat(asked).isTrue()
    }

    @Test
    fun `the analyse again row is dimmed while the track is waiting or being analysed`() {
        val sections = moreMenuSections(
            track,
            MoreMenuActions(onAddToPlaylist = {}, djMode = true, onReanalyse = {}, canReanalyse = false),
        )

        assertThat(sections.flatten().single { it.label == R.string.now_playing_reanalyse }.enabled).isFalse()
    }

    @Test
    fun `there is no analyse again row out of DJ mode or where there is no DJ screen`() {
        val off = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = false, onReanalyse = {}, canReanalyse = true))
        val elsewhere = moreMenuSections(track, MoreMenuActions(onAddToPlaylist = {}, djMode = true, onReanalyse = null, canReanalyse = true))

        assertThat(off.flatten().map { it.label }).doesNotContain(R.string.now_playing_reanalyse)
        assertThat(elsewhere.flatten().map { it.label }).doesNotContain(R.string.now_playing_reanalyse)
    }
}
