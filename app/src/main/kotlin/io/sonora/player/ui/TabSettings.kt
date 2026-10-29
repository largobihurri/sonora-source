package io.sonora.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.sonora.core.designsystem.motion.LocalTabStack
import io.sonora.core.designsystem.motion.PredictiveBackStack
import io.sonora.feature.settings.SettingsScreen
import io.sonora.player.navigation.TopLevelRoute

/** A page of a tab that hosts Settings over its own screen: the tab's screen, or Settings pushed on it. */
internal enum class TabPage { Tab, Settings }

/**
 * Whether this tab pushes Settings over its own screen from its top bar's gear. The Library does not: its own stack
 * holds Settings (`LibraryDest.Settings`), where the duplicates notification opens it too.
 */
internal fun TopLevelRoute.hostsSettings(): Boolean = this != TopLevelRoute.Library

/** The pages of [tab]: its screen, with Settings above it while Settings is [open] on it. */
internal fun settingsStack(open: Set<TopLevelRoute>, tab: TopLevelRoute): List<TabPage> =
    if (tab.hostsSettings() && tab in open) listOf(TabPage.Tab, TabPage.Settings) else listOf(TabPage.Tab)

/** Settings opened on [tab], kept apart from every other tab's; a tab that does not host Settings is left out. */
internal fun Set<TopLevelRoute>.withSettingsOn(tab: TopLevelRoute): Set<TopLevelRoute> = if (tab.hostsSettings()) this + tab else this

/** Settings closed on [tab]; the other tabs keep theirs. */
internal fun Set<TopLevelRoute>.withoutSettingsOn(tab: TopLevelRoute): Set<TopLevelRoute> = this - tab

/**
 * Whether the Settings stack ([outerDepth] pages) holds the tab for a tap on it (back to the tab's root, as Back does):
 * only while Settings is open. Otherwise the stack inside the tab's screen (the Converter's) holds it, as before.
 */
internal fun settingsHoldsTab(outerDepth: Int): Boolean = outerDepth > 1

/** The tabs with Settings open, kept across a configuration change and process death. */
@Composable
internal fun rememberTabSettings(): MutableState<Set<TopLevelRoute>> = rememberSaveable(saver = TabSettingsSaver) { mutableStateOf(emptySet()) }

private val TabSettingsSaver = Saver<MutableState<Set<TopLevelRoute>>, ArrayList<String>>(
    save = { state -> ArrayList(state.value.map { it.name }) },
    restore = { names -> mutableStateOf(names.mapNotNull { name -> TopLevelRoute.entries.find { it.name == name } }.toSet()) },
)

/**
 * [tab]'s [screen], given the way to open Settings over it (its top bar's gear), with Settings pushed on it while
 * [open] says so; Back, a swipe from the edge or a tap on the tab closes it again. [backEnabled] lets the shell keep Back
 * for Now Playing. Settings goes back to [tab] by name.
 */
@Composable
internal fun TabSettingsHost(
    tab: TopLevelRoute,
    open: Set<TopLevelRoute>,
    onOpenChange: (Set<TopLevelRoute>) -> Unit,
    backEnabled: Boolean,
    screen: @Composable (onOpenSettings: () -> Unit) -> Unit,
) {
    val pages = settingsStack(open, tab)
    val holds = settingsHoldsTab(pages.size)
    val tabStack = LocalTabStack.current
    // Only one stack holds the tab: this one while Settings is open, the screen's own otherwise.
    CompositionLocalProvider(LocalTabStack provides tabStack.takeIf { holds }) {
        PredictiveBackStack(
            stack = pages,
            onPop = { onOpenChange(open.withoutSettingsOn(tab)) },
            modifier = Modifier.fillMaxSize(),
            backEnabled = backEnabled,
        ) { page ->
            when (page) {
                TabPage.Tab -> CompositionLocalProvider(LocalTabStack provides tabStack.takeIf { !holds }) {
                    screen { onOpenChange(open.withSettingsOn(tab)) }
                }
                TabPage.Settings -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    SettingsScreen(
                        onBack = { onOpenChange(open.withoutSettingsOn(tab)) },
                        backLabel = stringResource(tab.label),
                    )
                }
            }
        }
    }
}
