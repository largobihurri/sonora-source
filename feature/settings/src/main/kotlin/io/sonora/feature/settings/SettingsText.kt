package io.sonora.feature.settings

import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ScanSummary
import io.sonora.core.domain.model.ThemeMode

/** Why a folder was not added or a setting not saved; the screen words it in the current language. */
sealed interface FolderNotice {
    data object AddFailed : FolderNotice

    data object PickerMissing : FolderNotice

    data object SaveFailed : FolderNotice

    data class Conflict(val conflict: FolderConflict) : FolderNotice
}

/** Title and message of an alert. */
data class NoticeText(val title: String, val message: String)

/** User-facing copy of the Settings screen from string resources, kept pure so it is unit-tested. */
object SettingsText {
    /** "Song · Beyoncé": what a hidden item is and, when known, whose. */
    fun hiddenItemLine(item: HiddenItem, strings: Strings): String {
        val kind = strings.get(
            when (item.ref.kind) {
                MarkKind.SONG -> R.string.settings_hidden_song
                MarkKind.ALBUM -> R.string.settings_hidden_album
                MarkKind.ARTIST -> R.string.settings_hidden_artist
                MarkKind.PLAYLIST -> R.string.settings_hidden_playlist
                MarkKind.FOLDER -> R.string.settings_hidden_folder
            },
        )
        return if (item.subtitle.isBlank()) kind else strings.get(R.string.settings_hidden_line, kind, item.subtitle)
    }

    fun notice(notice: FolderNotice, strings: Strings): NoticeText = when (notice) {
        FolderNotice.AddFailed ->
            NoticeText(strings.get(R.string.settings_add_failed_title), strings.get(R.string.settings_add_failed_message))
        FolderNotice.PickerMissing ->
            NoticeText(strings.get(R.string.settings_add_failed_title), strings.get(R.string.settings_picker_missing_message))
        FolderNotice.SaveFailed ->
            NoticeText(strings.get(R.string.settings_save_failed_title), strings.get(R.string.settings_save_failed_message))
        is FolderNotice.Conflict -> conflict(notice.conflict, strings)
    }

    private fun conflict(conflict: FolderConflict, strings: Strings): NoticeText {
        val existing = conflict.existing.displayName
        val (title, message) = when (conflict) {
            is FolderConflict.AlreadyAdded -> R.string.settings_already_added_title to R.string.settings_already_added_message
            is FolderConflict.InsideExisting -> R.string.settings_inside_existing_title to R.string.settings_inside_existing_message
            is FolderConflict.ContainsExisting ->
                R.string.settings_contains_existing_title to R.string.settings_contains_existing_message
        }
        return NoticeText(strings.get(title), strings.get(message, existing))
    }

    fun themeLabel(theme: ThemeMode, strings: Strings): String = strings.get(
        when (theme) {
            ThemeMode.SYSTEM -> R.string.settings_theme_system
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )

    fun accentModeTitle(mode: AccentMode, strings: Strings): String = strings.get(
        when (mode) {
            AccentMode.MATERIAL_YOU -> R.string.settings_accent_material_you
            AccentMode.PRESET -> R.string.settings_accent_palette
            AccentMode.ARTWORK -> R.string.settings_accent_artwork
        },
    )

    /** The line under an accent choice; Material You explains its fallback before Android 12 ([sdkInt] < 31). */
    fun accentModeSubtitle(mode: AccentMode, sdkInt: Int, strings: Strings): String? = when (mode) {
        AccentMode.MATERIAL_YOU -> strings.get(
            if (sdkInt >= MATERIAL_YOU_MIN_SDK) R.string.settings_accent_material_you_subtitle else R.string.settings_accent_material_you_unavailable,
        )
        AccentMode.PRESET -> null
        AccentMode.ARTWORK -> strings.get(R.string.settings_accent_artwork_subtitle)
    }

    /**
     * The line under the Translucency switch: what this Android version ([sdkInt]) can draw, or, when the system asks
     * for more contrast ([highContrast], see [TranslucentPolicy.resolve]), that the translucency is flat and more
     * opaque. When this device's GPU did not keep up and the translucency was lowered to [limitedTo]
     * ([TranslucentPolicy.limited]), it says so and how to try the full translucency again.
     */
    fun translucencyFooter(sdkInt: Int, highContrast: Boolean, strings: Strings, limitedTo: TranslucentTier? = null): String {
        val tier = TranslucentPolicy.tierFor(sdkInt)
        return strings.get(
            when {
                tier == TranslucentTier.FLAT -> R.string.settings_translucency_footer_flat
                highContrast -> R.string.settings_translucency_footer_contrast
                limitedTo == TranslucentTier.FLAT -> R.string.settings_translucency_footer_limited_flat
                limitedTo != null -> R.string.settings_translucency_footer_limited_frosted
                tier == TranslucentTier.LIQUID -> R.string.settings_translucency_footer_liquid
                else -> R.string.settings_translucency_footer_frosted
            },
        )
    }

    /** Spoken name of a palette colour. */
    fun presetName(preset: AccentPreset, strings: Strings): String = strings.get(
        when (preset) {
            AccentPreset.RED -> R.string.settings_preset_red
            AccentPreset.ORANGE -> R.string.settings_preset_orange
            AccentPreset.AMBER -> R.string.settings_preset_amber
            AccentPreset.GREEN -> R.string.settings_preset_green
            AccentPreset.TEAL -> R.string.settings_preset_teal
            AccentPreset.BLUE -> R.string.settings_preset_blue
            AccentPreset.INDIGO -> R.string.settings_preset_indigo
            AccentPreset.PURPLE -> R.string.settings_preset_purple
            AccentPreset.PINK -> R.string.settings_preset_pink
        },
    )

    /** A language's own name for itself, the same whatever language the app is in. */
    fun languageName(language: AppLanguage, strings: Strings): String = strings.get(
        when (language) {
            AppLanguage.SPANISH -> R.string.settings_language_spanish
            AppLanguage.ENGLISH -> R.string.settings_language_english
            AppLanguage.BASQUE -> R.string.settings_language_basque
        },
    )

    private const val MATERIAL_YOU_MIN_SDK = 31

    /** Status line under "Rescan Library"; `null` when there is nothing to report. */
    fun scanStatus(progress: ScanProgress, strings: Strings): String? = when (progress) {
        ScanProgress.Idle -> null
        is ScanProgress.Scanning ->
            if (progress.total == 0) {
                strings.get(R.string.settings_scanning, progress.folderName)
            } else {
                strings.get(R.string.settings_scanning_progress, progress.folderName, progress.scanned, progress.total)
            }
        is ScanProgress.Finished -> lastScan(progress.summary, strings)
    }

    fun lastScan(summary: ScanSummary, strings: Strings): String {
        val changes = listOfNotNull(
            summary.added.takeIf { it > 0 }?.let { strings.plural(R.plurals.settings_scan_added, it, it) },
            summary.changed.takeIf { it > 0 }?.let { strings.plural(R.plurals.settings_scan_updated, it, it) },
            summary.removed.takeIf { it > 0 }?.let { strings.plural(R.plurals.settings_scan_removed, it, it) },
        )
        val total = summary.added + summary.changed + summary.unchanged
        val lines = mutableListOf(
            if (changes.isEmpty()) {
                strings.get(R.string.settings_last_scan_no_changes, strings.plural(R.plurals.settings_scan_songs, total, total))
            } else {
                strings.get(R.string.settings_last_scan_changes, changes.joinToString(strings.get(R.string.settings_list_separator)))
            },
        )
        if (summary.skipped > 0) {
            lines += strings.plural(R.plurals.settings_scan_skipped, summary.skipped, summary.skipped)
        }
        if (summary.failedFolders > 0) {
            lines += strings.plural(R.plurals.settings_scan_failed_folders, summary.failedFolders, summary.failedFolders)
        }
        return lines.joinToString("\n")
    }
}
