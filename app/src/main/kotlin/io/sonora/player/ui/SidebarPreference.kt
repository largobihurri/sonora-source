package io.sonora.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.port.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The shell's navigation choice on large screens: whether the sidebar is shown, kept per device. */
@HiltViewModel
class NavigationViewModel @Inject constructor(preferences: PreferencesRepository) : ViewModel() {
    private val sidebar = SidebarPreference(viewModelScope, preferences)

    /** `null` until the settings are read. */
    val sidebarShown: StateFlow<Boolean?> = sidebar.shown

    fun setSidebarShown(shown: Boolean) = sidebar.set(shown)
}

/** [NavigationViewModel]'s flow and write over any [scope], so they are testable without Android's main thread. */
internal class SidebarPreference(private val scope: CoroutineScope, private val preferences: PreferencesRepository) {
    val shown: StateFlow<Boolean?> = preferences.preferences
        .map { it.navigation.sidebarShown }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Stores [shown] and leaves every other setting as it is. */
    fun set(shown: Boolean) {
        scope.launch { preferences.update { it.copy(navigation = it.navigation.copy(sidebarShown = shown)) } }
    }
}
