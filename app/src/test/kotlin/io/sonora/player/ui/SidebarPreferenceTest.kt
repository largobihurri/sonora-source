package io.sonora.player.ui

import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.NavigationPreferences
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.port.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sidebar choice the shell reads; collected on an unconfined scope, so every emit is seen. */
class SidebarPreferenceTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    /** Stores nothing until [emit]; [update] applies to what was emitted last and emits the result. */
    private class FakePreferences : PreferencesRepository {
        private val flow = MutableSharedFlow<AppPreferences>(replay = 1)
        var current = AppPreferences()
            private set
        override val preferences: Flow<AppPreferences> = flow

        fun emit(value: AppPreferences) {
            current = value
            flow.tryEmit(value)
        }

        override suspend fun update(transform: (AppPreferences) -> AppPreferences) = emit(transform(current))
    }

    private val preferences = FakePreferences()
    private val sidebar = SidebarPreference(scope, preferences)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `nothing is known until the settings are read, then the stored choice`() {
        assertNull(sidebar.shown.value)

        preferences.emit(AppPreferences(navigation = NavigationPreferences(sidebarShown = false)))

        assertEquals(false, sidebar.shown.value)
    }

    @Test
    fun `a fresh install shows the sidebar`() {
        preferences.emit(AppPreferences())

        assertEquals(true, sidebar.shown.value)
    }

    @Test
    fun `hiding the sidebar stores only that choice`() {
        val others = AppPreferences(nowPlaying = NowPlayingPreferences(showLyricsButton = true))
        preferences.emit(others)

        sidebar.set(false)

        assertEquals(false, sidebar.shown.value)
        assertEquals(others.copy(navigation = NavigationPreferences(sidebarShown = false)), preferences.current)
    }

    @Test
    fun `showing it again is stored too`() {
        preferences.emit(AppPreferences(navigation = NavigationPreferences(sidebarShown = false)))

        sidebar.set(true)

        assertEquals(true, sidebar.shown.value)
    }
}
