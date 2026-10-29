package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextMenuHostStateTest {

    private val state = ContextMenuHostState()
    private fun menu() = ContextMenuContent(card = {}, menu = {})

    @Test
    fun `a menu item's action runs once the menu has closed`() {
        val ran = mutableListOf<String>()
        state.show(menu())
        state.dismiss { ran += "go to album" }
        assertThat(ran).isEmpty()
        state.finished()
        assertThat(ran).containsExactly("go to album")
        assertThat(state.isShowing).isFalse()
    }

    @Test
    fun `a new menu during the close runs the closing menu's action instead of dropping it`() {
        val ran = mutableListOf<String>()
        state.show(menu())
        state.dismiss { ran += "remove" }
        // Long-pressed again before the close animation ended.
        val next = menu()
        state.show(next)
        assertThat(ran).containsExactly("remove")
        assertThat(state.content).isSameInstanceAs(next)
        assertThat(state.open).isTrue()

        // The new menu closing on its own runs nothing more.
        state.dismiss()
        state.finished()
        assertThat(ran).containsExactly("remove")
    }

    @Test
    fun `a dismiss without an action keeps the one already chosen`() {
        val ran = mutableListOf<String>()
        state.show(menu())
        state.dismiss { ran += "share" }
        state.dismiss()
        state.finished()
        assertThat(ran).containsExactly("share")
    }
}
