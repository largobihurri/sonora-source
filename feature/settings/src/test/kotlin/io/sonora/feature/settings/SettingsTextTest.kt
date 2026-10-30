package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ScanSummary
import io.sonora.core.domain.model.ThemeMode
import org.junit.Test

class SettingsTextTest {

    private val music = LibraryFolder("content://tree/music", "Music", 1)
    private val en = XmlStrings.of()

    @Test
    fun `conflicts name the folder already in the library`() {
        assertThat(SettingsText.notice(FolderNotice.Conflict(FolderConflict.InsideExisting(music)), en).message)
            .isEqualTo("This folder is inside “Music”, which is already in your library.")
        assertThat(SettingsText.notice(FolderNotice.Conflict(FolderConflict.ContainsExisting(music)), en).message)
            .contains("Remove “Music” first")
        assertThat(SettingsText.notice(FolderNotice.Conflict(FolderConflict.AlreadyAdded(music)), en).title)
            .isEqualTo("Folder Already Added")
    }

    @Test
    fun `hidden items say what they are and, when known, whose`() {
        val es = XmlStrings.of("-es")
        val eu = XmlStrings.of("-eu")
        val song = HiddenItem(ItemRef.song("content://music/1"), "Halo", "Beyoncé", 1)
        val playlist = HiddenItem(ItemRef.playlist(3L), "Gym", "", 1)

        assertThat(SettingsText.hiddenItemLine(song, en)).isEqualTo("Song · Beyoncé")
        assertThat(SettingsText.hiddenItemLine(playlist, en)).isEqualTo("Playlist")
        assertThat(SettingsText.hiddenItemLine(song, es)).isEqualTo("Canción · Beyoncé")
        assertThat(SettingsText.hiddenItemLine(playlist, eu)).isEqualTo("Zerrenda")
    }

    @Test
    fun `failures explain themselves`() {
        assertThat(SettingsText.notice(FolderNotice.AddFailed, en)).isEqualTo(
            NoticeText("Couldn't Add Folder", "Sonora can't read that folder. Choose a folder on your device's storage."),
        )
        assertThat(SettingsText.notice(FolderNotice.PickerMissing, en))
            .isEqualTo(NoticeText("Couldn't Add Folder", "This device has no app for choosing folders."))
        assertThat(SettingsText.notice(FolderNotice.SaveFailed, en))
            .isEqualTo(NoticeText("Couldn't Save Setting", "Sonora couldn't save this change. Try again."))
    }

    @Test
    fun `scan status shows the folder and file progress`() {
        assertThat(SettingsText.scanStatus(ScanProgress.Idle, en)).isNull()
        assertThat(SettingsText.scanStatus(ScanProgress.Scanning("Music", 0, 0), en)).isEqualTo("Scanning “Music”…")
        assertThat(SettingsText.scanStatus(ScanProgress.Scanning("Music", 120, 1260), en))
            .isEqualTo("Scanning “Music”… 120 of 1260")
    }

    @Test
    fun `last scan summary lists changes, retries and unreadable folders`() {
        assertThat(SettingsText.lastScan(ScanSummary(added = 12, changed = 1, removed = 2, unchanged = 5), en))
            .isEqualTo("Last scan: 12 added, 1 updated, 2 removed.")
        assertThat(SettingsText.lastScan(ScanSummary(unchanged = 1), en))
            .isEqualTo("Last scan: no changes, 1 song.")
        assertThat(SettingsText.lastScan(ScanSummary(unchanged = 3, skipped = 2, failedFolders = 1), en)).isEqualTo(
            "Last scan: no changes, 3 songs.\n2 files couldn't be read and will be retried next scan.\n" +
                "1 folder couldn't be opened; its songs were kept.",
        )
    }

    @Test
    fun `the scan summary reads in Basque, with no plural ending after a number`() {
        val eu = XmlStrings.of("-eu")

        assertThat(SettingsText.lastScan(ScanSummary(unchanged = 3), eu)).contains("3 abesti")
        assertThat(SettingsText.lastScan(ScanSummary(unchanged = 3), eu)).doesNotContain("abestiak")
    }

    @Test
    fun `accent choices explain where the colour comes from, and the Material You fallback before Android 12`() {
        assertThat(SettingsText.accentModeTitle(AccentMode.MATERIAL_YOU, en)).isEqualTo("Material You")
        assertThat(SettingsText.accentModeTitle(AccentMode.PRESET, en)).isEqualTo("Colour Palette")
        assertThat(SettingsText.accentModeTitle(AccentMode.ARTWORK, en)).isEqualTo("Follow Artwork")
        assertThat(SettingsText.accentModeSubtitle(AccentMode.MATERIAL_YOU, sdkInt = 31, en)).isEqualTo("Colours from your wallpaper")
        assertThat(SettingsText.accentModeSubtitle(AccentMode.MATERIAL_YOU, sdkInt = 30, en))
            .isEqualTo("Needs Android 12; Sonora Pink is used instead")
        assertThat(SettingsText.accentModeSubtitle(AccentMode.ARTWORK, sdkInt = 36, en)).isEqualTo("Colours from the song that's playing")
        assertThat(SettingsText.accentModeSubtitle(AccentMode.PRESET, sdkInt = 36, en)).isNull()
    }

    @Test
    fun `themes and presets have names`() {
        assertThat(ThemeMode.entries.map { SettingsText.themeLabel(it, en) }).containsExactly("System", "Light", "Dark").inOrder()
        assertThat(SettingsText.presetName(AccentPreset.PINK, en)).isEqualTo("Pink")
        assertThat(AccentPreset.entries.map { SettingsText.presetName(it, en) }.toSet()).hasSize(AccentPreset.entries.size)
    }

    @Test
    fun `the Translucency footer says what this Android version can draw`() {
        assertThat(SettingsText.translucencyFooter(33, highContrast = false, en)).isEqualTo(
            "Refraction, blur and a light rim on the tab bar, mini player and buttons. Off uses a flat translucent surface.",
        )
        assertThat(SettingsText.translucencyFooter(31, highContrast = false, en)).isEqualTo(
            "This Android version blurs the translucent surfaces without refraction. Off uses a flat translucent surface.",
        )
        assertThat(SettingsText.translucencyFooter(30, highContrast = false, en)).isEqualTo(
            "This Android version always uses a flat translucent surface.",
        )
    }

    @Test
    fun `on a device whose GPU did not keep up, the Translucency footer says so and how to try again`() {
        assertThat(SettingsText.translucencyFooter(36, highContrast = false, en, limitedTo = TranslucentTier.FROSTED)).isEqualTo(
            "This device's graphics chip could not keep up with the translucent effect, so it is blurred without refraction. " +
                "Turn the switch off and on to try again.",
        )
        assertThat(SettingsText.translucencyFooter(36, highContrast = false, en, limitedTo = TranslucentTier.FLAT)).isEqualTo(
            "This device's graphics chip could not keep up with the translucent effect, so it uses a flat translucent " +
                "surface. Turn the switch off and on to try again.",
        )
        assertThat(SettingsText.translucencyFooter(36, highContrast = false, XmlStrings.of("-es"), limitedTo = TranslucentTier.FLAT)).isEqualTo(
            "El chip gráfico de este dispositivo no podía con el efecto translúcido, así que se usa una superficie translúcida " +
                "plana. Desactiva y vuelve a activar el interruptor para probar de nuevo.",
        )
        assertThat(SettingsText.translucencyFooter(36, highContrast = false, XmlStrings.of("-eu"), limitedTo = TranslucentTier.FROSTED)).isEqualTo(
            "Gailu honen txip grafikoak ezin izan dio efektu zeharrargiari eutsi; beraz, errefrakziorik gabe lausotzen " +
                "da. Desaktibatu eta aktibatu berriro etengailua berriz probatzeko.",
        )
        // More contrast is still the reason when it is asked for, and no limit keeps the usual footer.
        assertThat(SettingsText.translucencyFooter(36, highContrast = true, en, limitedTo = TranslucentTier.FLAT))
            .isEqualTo("Android's contrast settings ask for more contrast, so the translucent surfaces are flat and more opaque.")
        assertThat(SettingsText.translucencyFooter(36, highContrast = false, en, limitedTo = null)).isEqualTo(
            "Refraction, blur and a light rim on the tab bar, mini player and buttons. Off uses a flat translucent surface.",
        )
    }

    @Test
    fun `with more contrast asked for, the Translucency footer says the translucent surfaces are flat`() {
        val contrast = "Android's contrast settings ask for more contrast, so the translucent surfaces are flat and more opaque."
        assertThat(SettingsText.translucencyFooter(33, highContrast = true, en)).isEqualTo(contrast)
        assertThat(SettingsText.translucencyFooter(31, highContrast = true, en)).isEqualTo(contrast)
        // A version that never draws translucency says so whatever the contrast.
        assertThat(SettingsText.translucencyFooter(30, highContrast = true, en))
            .isEqualTo("This Android version always uses a flat translucent surface.")
        assertThat(SettingsText.translucencyFooter(33, highContrast = true, XmlStrings.of("-es"))).isEqualTo(
            "Los ajustes de contraste de Android piden más contraste, así que las superficies translúcidas son planas y más opacas.",
        )
        assertThat(SettingsText.translucencyFooter(33, highContrast = true, XmlStrings.of("-eu"))).isEqualTo(
            "Android-en kontraste-ezarpenek kontraste handiagoa eskatzen dute; beraz, gainazal zeharrargiak lauak eta opakuagoak dira.",
        )
    }

    @Test
    fun `each language is named in itself`() {
        assertThat(io.sonora.core.domain.model.AppLanguage.entries.map { SettingsText.languageName(it, en) })
            .containsExactly("Castellano", "English", "Euskara").inOrder()
        assertThat(SettingsText.languageName(io.sonora.core.domain.model.AppLanguage.BASQUE, XmlStrings.of("-es")))
            .isEqualTo("Euskara")
    }
}
