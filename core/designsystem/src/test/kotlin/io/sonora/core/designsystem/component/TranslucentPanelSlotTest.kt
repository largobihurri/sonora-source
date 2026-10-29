package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentPanelSlotTest {

    private val slot = TranslucentPanelSlot()
    private val dismissed = mutableListOf<String>()

    private fun entry(name: String) = TranslucentPanelEntry(
        owner = name,
        title = { name },
        anchor = { null },
        content = {},
        onDismiss = { dismissed += name },
    )

    @Test
    fun `an alert shown over a panel covers it and the panel comes back once the alert has closed`() {
        val panel = entry("panel")
        val alert = entry("alert")
        slot.show(panel)
        slot.show(alert)

        assertThat(slot.entry).isSameInstanceAs(alert)
        assertThat(slot.covered).containsExactly(panel)
        assertThat(slot.open).isTrue()

        slot.hide("alert")
        assertThat(slot.open).isFalse()
        assertThat(slot.covered).containsExactly(panel)

        slot.finished()
        assertThat(slot.entry).isSameInstanceAs(panel)
        assertThat(slot.covered).isEmpty()
        assertThat(slot.open).isTrue()
        assertThat(slot.isRevealed(panel)).isTrue()
    }

    @Test
    fun `back and a tap outside dismiss only the top panel`() {
        slot.show(entry("panel"))
        slot.show(entry("alert"))

        slot.requestDismiss()

        assertThat(dismissed).containsExactly("alert")
    }

    @Test
    fun `a covered panel that leaves goes at once and the alert stays`() {
        val alert = entry("alert")
        slot.show(entry("panel"))
        slot.show(alert)

        slot.hide("panel")

        assertThat(slot.entry).isSameInstanceAs(alert)
        assertThat(slot.covered).isEmpty()
        assertThat(slot.open).isTrue()
    }

    @Test
    fun `a panel shown while the last one closes takes its place`() {
        val next = entry("next")
        slot.show(entry("first"))
        slot.hide("first")

        slot.show(next)

        assertThat(slot.entry).isSameInstanceAs(next)
        assertThat(slot.covered).isEmpty()
        assertThat(slot.open).isTrue()
        assertThat(slot.isRevealed(next)).isFalse()
    }

    @Test
    fun `the last panel closing leaves the slot empty`() {
        slot.show(entry("panel"))
        slot.hide("panel")
        slot.finished()

        assertThat(slot.entry).isNull()
        assertThat(slot.open).isFalse()
    }

    @Test
    fun `hiding a panel someone else replaced changes nothing`() {
        val second = entry("second")
        slot.show(entry("first"))
        slot.hide("first")
        slot.show(second)

        slot.hide("first")

        assertThat(slot.entry).isSameInstanceAs(second)
        assertThat(slot.open).isTrue()
    }
}
