package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolume.State
import org.junit.Test

class StickEjectTest {

    private val stick = volume("2C9C-2151", "SONORA")
    private val card = volume("3333-4444", "SD card")

    @Test
    fun `ejecting the chosen stick opens the most specific storage screen first`() {
        val state = StickEject.request("2C9C-2151", listOf(card, stick))

        assertThat(state).isEqualTo(
            EjectState.OpenScreen(
                stick,
                listOf(EjectScreen.VOLUME_SETTINGS, EjectScreen.STORAGE_SETTINGS, EjectScreen.MEMORY_CARD_SETTINGS),
            ),
        )
    }

    @Test
    fun `without a chosen stick the only volume in use is the one to eject`() {
        val checking = volume("2C9C-2151", "SONORA", State.CHECKING)

        assertThat(StickEject.request(null, listOf(volume("1111-2222", "Old", State.UNMOUNTED), checking)))
            .isEqualTo(EjectState.OpenScreen(checking, StickEject.screensFor(checking)))
    }

    @Test
    fun `a chosen stick that is gone is not swapped for another volume`() {
        // The one left may be an SD card: it must not be offered for ejecting in the stick's place.
        assertThat(StickEject.request("9999-0000", listOf(card))).isEqualTo(EjectState.SafeToRemove(null))
    }

    @Test
    fun `with several in use and none chosen only the general storage screens are tried`() {
        assertThat(StickEject.request(null, listOf(card, stick)))
            .isEqualTo(EjectState.OpenScreen(null, listOf(EjectScreen.STORAGE_SETTINGS, EjectScreen.MEMORY_CARD_SETTINGS)))
    }

    @Test
    fun `a stick Android no longer holds is safe to remove at once`() {
        assertThat(StickEject.request("2C9C-2151", listOf(volume("2C9C-2151", "SONORA", State.UNMOUNTED))))
            .isEqualTo(EjectState.SafeToRemove("SONORA"))
        assertThat(StickEject.request(null, emptyList())).isEqualTo(EjectState.SafeToRemove(null))
    }

    @Test
    fun `the stick is safe to remove once Android unmounts it, not while it is ejecting`() {
        var state = StickEject.request("2C9C-2151", listOf(stick))
        state = StickEject.onScreenOpened(state, EjectScreen.STORAGE_SETTINGS)
        assertThat(state).isEqualTo(EjectState.Waiting(stick, EjectScreen.STORAGE_SETTINGS))

        state = StickEject.onVolumes(state, listOf(card, volume("2C9C-2151", "SONORA", State.EJECTING)))
        assertThat(state).isEqualTo(EjectState.Waiting(stick, EjectScreen.STORAGE_SETTINGS))

        state = StickEject.onVolumes(state, listOf(card, volume("2C9C-2151", "SONORA", State.UNMOUNTED)))
        assertThat(state).isEqualTo(EjectState.SafeToRemove("SONORA"))
    }

    @Test
    fun `a stick that leaves the list is no longer held either`() {
        val waiting = StickEject.onScreenOpened(StickEject.request("2C9C-2151", listOf(stick)), null)
        assertThat(waiting).isEqualTo(EjectState.Waiting(stick, null))

        assertThat(StickEject.onVolumes(waiting, listOf(card))).isEqualTo(EjectState.SafeToRemove("SONORA"))
    }

    @Test
    fun `an unknown target is released when no volume is in use any more`() {
        val waiting = StickEject.onScreenOpened(StickEject.request(null, listOf(card, stick)), EjectScreen.STORAGE_SETTINGS)

        assertThat(StickEject.onVolumes(waiting, listOf(card, volume("2C9C-2151", "SONORA", State.UNMOUNTED))))
            .isEqualTo(waiting)
        assertThat(StickEject.onVolumes(waiting, listOf(volume("3333-4444", "SD card", State.REMOVED))))
            .isEqualTo(EjectState.SafeToRemove(null))
    }

    @Test
    fun `the unmount can come before the screen reports it opened`() {
        val opening = StickEject.request("2C9C-2151", listOf(stick))

        val safe = StickEject.onVolumes(opening, listOf(volume("2C9C-2151", "SONORA", State.UNMOUNTED)))

        assertThat(safe).isEqualTo(EjectState.SafeToRemove("SONORA"))
        assertThat(StickEject.onScreenOpened(safe, EjectScreen.VOLUME_SETTINGS)).isEqualTo(safe)
    }

    @Test
    fun `volume changes mean nothing while no eject was asked for`() {
        assertThat(StickEject.onVolumes(EjectState.Idle, emptyList())).isEqualTo(EjectState.Idle)
        assertThat(StickEject.onScreenOpened(EjectState.Idle, EjectScreen.STORAGE_SETTINGS)).isEqualTo(EjectState.Idle)
    }

    private fun volume(uuid: String, name: String, state: State = State.MOUNTED) =
        RemovableVolume(uuid, name, totalBytes = 16L shl 30, state = state)
}
