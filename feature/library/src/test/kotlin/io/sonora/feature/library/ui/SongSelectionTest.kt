package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.library.libraryTrack
import org.junit.Test

class SongSelectionTest {

    private val tracks = listOf(libraryTrack(1), libraryTrack(2), libraryTrack(3))
    private val shown = tracks.map { it.uri to it }
    private val keys = shown.map { it.first }

    @Test
    fun `Select starts with nothing picked, a long-press with its song`() {
        assertThat(SelectionState().active).isFalse()
        assertThat(SelectionState().start()).isEqualTo(SelectionState(active = true))
        assertThat(SelectionState().start(keys[1]).keys).containsExactly(keys[1])
    }

    @Test
    fun `tapping toggles a song only while selecting`() {
        val selecting = SelectionState().start().toggle(keys[0]).toggle(keys[2]).toggle(keys[0])
        assertThat(selecting.keys).containsExactly(keys[2])
        assertThat(SelectionState().toggle(keys[0])).isEqualTo(SelectionState())
    }

    @Test
    fun `the picked songs come in the order shown, whatever the order tapped`() {
        val selecting = SelectionState().start().toggle(keys[2]).toggle(keys[0])
        assertThat(selecting.picked(shown)).containsExactly(tracks[0], tracks[2]).inOrder()
    }

    @Test
    fun `songs no longer shown are not counted or acted on`() {
        val selecting = SelectionState().start().toggle(keys[0]).toggle("content://gone")
        assertThat(selecting.picked(shown)).containsExactly(tracks[0])
        assertThat(selecting.count(keys)).isEqualTo(1)
    }

    @Test
    fun `Select All picks every song shown, then Deselect All clears them`() {
        val all = SelectionState().start().toggleAll(keys)
        assertThat(all.allSelected(keys)).isTrue()
        assertThat(all.count(keys)).isEqualTo(3)
        val none = all.toggleAll(keys)
        assertThat(none.keys).isEmpty()
        assertThat(none.active).isTrue()
        assertThat(SelectionState().start().allSelected(emptyList())).isFalse()
    }

    @Test
    fun `Done and Back end the selection`() {
        assertThat(SelectionState().start(keys[0]).exit()).isEqualTo(SelectionState())
    }

    @Test
    fun `a playlist's repeated song is picked once per row`() {
        val repeated = listOf("entry:1" to tracks[0], "entry:2" to tracks[0])
        assertThat(SelectionState().start().toggle("entry:2").toggle("entry:1").picked(repeated)).containsExactly(tracks[0], tracks[0])
    }

    @Test
    fun `the bar offers Compress and the existing song actions, none with nothing picked`() {
        val bar = SelectionBarModel.of(count = 2, canExport = true)
        assertThat(bar.actions).containsExactly(
            SelectionAction.COMPRESS, SelectionAction.ADD_TO_PLAYLIST, SelectionAction.EXPORT_REKORDBOX,
            SelectionAction.PLAY_NEXT, SelectionAction.PLAY_LAST,
        ).inOrder()
        assertThat(bar.disabled).isEmpty()
        assertThat(SelectionBarModel.of(count = 0, canExport = true).disabled).containsExactlyElementsIn(bar.actions)
        assertThat(SelectionBarModel.of(count = 2, canExport = false).disabled).containsExactly(SelectionAction.EXPORT_REKORDBOX)
    }

    @Test
    fun `the holder marks rows only while selecting`() {
        val selection = SongSelection()
        assertThat(selection.mark(keys[0])).isNull()

        selection.start(keys[0])

        assertThat(selection.active).isTrue()
        assertThat(selection.mark(keys[0])).isTrue()
        assertThat(selection.mark(keys[1])).isFalse()
    }

    @Test
    fun `a row's tap and long-press do their usual thing until selecting, then pick the row`() {
        val selection = SongSelection()
        val calls = mutableListOf<String>()
        val tap = selection.click(keys[1]) { calls += "play" }
        val press = selection.longClick(keys[1]) { calls += "menu" }

        tap()
        press()
        assertThat(calls).containsExactly("play", "menu").inOrder()
        assertThat(selection.state.keys).isEmpty()

        // The same handlers, made before the selection started, follow it.
        selection.start()
        tap()
        assertThat(selection.mark(keys[1])).isTrue()
        press()
        assertThat(selection.mark(keys[1])).isFalse()
        assertThat(calls).hasSize(2)
    }

    @Test
    fun `the holder's Select All and Done`() {
        val selection = SongSelection()
        selection.start()
        selection.toggleAll(keys)
        assertThat(selection.state.count(keys)).isEqualTo(3)

        selection.exit()

        assertThat(selection.active).isFalse()
        assertThat(selection.mark(keys[0])).isNull()
    }
}
