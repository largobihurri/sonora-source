package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolume.State
import org.junit.Test

class StickShortcutTest {

    private val stick = volume("2C9C-2151", "SONORA")
    private val card = volume("3333-4444", "SD card")

    @Test
    fun `a mounted stick Sonora already has access to opens with no picker`() {
        val grant = tree("2C9C-2151")

        val shortcut = StickShortcuts.choose(listOf(stick), setOf(tree("1111-2222"), grant))

        assertThat(shortcut).isEqualTo(StickShortcut.Open(stick, grant))
    }

    @Test
    fun `the grant is used as it was given, whatever the case of the volume id`() {
        val grant = "content://com.android.externalstorage.documents/tree/2c9c-2151%3A"

        assertThat(StickShortcuts.choose(listOf(stick), setOf(grant))).isEqualTo(StickShortcut.Open(stick, grant))
    }

    @Test
    fun `without access to its root the picker opens at the stick`() {
        val grants = setOf(
            "content://com.android.externalstorage.documents/tree/2C9C-2151%3AMusic",
            "content://com.android.externalstorage.documents/tree/primary%3A",
            "content://com.example.documents/tree/2C9C-2151%3A",
        )

        assertThat(StickShortcuts.choose(listOf(stick), grants)).isEqualTo(StickShortcut.PickAt(stick))
    }

    @Test
    fun `only a volume mounted for writing is a stick to export to`() {
        val volumes = listOf(
            volume("1111-2222", "Old", State.UNMOUNTED),
            volume("5555-6666", "Locked", State.MOUNTED_READ_ONLY),
            volume("7777-8888", "Busy", State.CHECKING),
            stick,
        )

        assertThat(StickShortcuts.choose(volumes, setOf(tree("2C9C-2151"), tree("5555-6666"))))
            .isEqualTo(StickShortcut.Open(stick, tree("2C9C-2151")))
    }

    @Test
    fun `with several mounted the user chooses, and each opens as it can`() {
        // An SD card and a stick look alike to apps: neither is chosen for the user.
        val grants = setOf(tree("2C9C-2151"))

        assertThat(StickShortcuts.choose(listOf(card, stick), grants)).isEqualTo(StickShortcut.Choose(listOf(card, stick)))
        assertThat(StickShortcuts.forVolume(stick, grants)).isEqualTo(StickShortcut.Open(stick, tree("2C9C-2151")))
        assertThat(StickShortcuts.forVolume(card, grants)).isEqualTo(StickShortcut.PickAt(card))
    }

    @Test
    fun `with none mounted the picker opens where it likes`() {
        assertThat(StickShortcuts.choose(emptyList(), setOf(tree("2C9C-2151")))).isEqualTo(StickShortcut.Picker)
        assertThat(StickShortcuts.choose(listOf(volume("2C9C-2151", "SONORA", State.EJECTING)), emptySet()))
            .isEqualTo(StickShortcut.Picker)
    }

    @Test
    fun `the volume of a storage tree is known only for the root of a removable volume`() {
        assertThat(StickShortcuts.volumeOf(tree("2C9C-2151"))).isEqualTo("2C9C-2151")
        assertThat(StickShortcuts.volumeOf("content://com.android.externalstorage.documents/tree/2C9C-2151%3AMusic"))
            .isNull()
        assertThat(StickShortcuts.volumeOf("content://com.android.externalstorage.documents/tree/primary%3A")).isNull()
        assertThat(StickShortcuts.volumeOf("content://com.example.documents/tree/2C9C-2151%3A")).isNull()
        assertThat(StickShortcuts.volumeOf("content://com.android.externalstorage.documents/tree/%3A")).isNull()
        assertThat(StickShortcuts.volumeOf("not a uri")).isNull()
    }

    private fun tree(uuid: String) = "content://com.android.externalstorage.documents/tree/$uuid%3A"

    private fun volume(uuid: String, name: String, state: State = State.MOUNTED) =
        RemovableVolume(uuid, name, totalBytes = 16L shl 30, state = state)
}
