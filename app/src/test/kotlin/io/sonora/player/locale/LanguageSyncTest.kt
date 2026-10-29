package io.sonora.player.locale

import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.port.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The language Sonora applies at start, when the user picks one in Settings, and when Android's settings change it. */
class LanguageSyncTest {

    private class FakePreferences(initial: AppPreferences = AppPreferences()) : PreferencesRepository {
        override val preferences = MutableStateFlow(initial)

        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            preferences.value = transform(preferences.value)
        }

        fun choose(language: AppLanguage) {
            preferences.value = preferences.value.copy(language = language)
        }
    }

    /** Android's per-app language: what the system holds for Sonora, and every language Sonora applied. */
    private class FakeGateway(var system: AppLanguage? = null, private val systemFollowsApply: Boolean = true) : AppLocaleGateway {
        val applied = mutableListOf<AppLanguage>()

        override fun systemChoice(): AppLanguage? = system

        override fun apply(language: AppLanguage) {
            applied += language
            if (systemFollowsApply) system = language
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After
    fun tearDown() = scope.cancel()

    private fun start(sync: LanguageSync) {
        scope.launch { sync.run() }
    }

    @Test
    fun `a first start with nothing chosen applies Castellano`() {
        val gateway = FakeGateway(system = null)
        start(LanguageSync(FakePreferences(), gateway))

        assertEquals(listOf(AppLanguage.SPANISH), gateway.applied)
    }

    @Test
    fun `the stored choice is applied at start`() {
        val gateway = FakeGateway(system = null)
        start(LanguageSync(FakePreferences(AppPreferences(language = AppLanguage.BASQUE)), gateway))

        assertEquals(listOf(AppLanguage.BASQUE), gateway.applied)
    }

    @Test
    fun `a language picked in Android's app language settings is taken over instead of overwritten`() {
        val preferences = FakePreferences()
        val gateway = FakeGateway(system = AppLanguage.ENGLISH)
        start(LanguageSync(preferences, gateway))

        assertEquals(AppLanguage.ENGLISH, preferences.preferences.value.language)
        assertEquals(listOf(AppLanguage.ENGLISH), gateway.applied)
    }

    @Test
    fun `picking a language in Settings applies it`() {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        start(LanguageSync(preferences, gateway))

        preferences.choose(AppLanguage.ENGLISH)
        preferences.choose(AppLanguage.BASQUE)

        assertEquals(listOf(AppLanguage.SPANISH, AppLanguage.ENGLISH, AppLanguage.BASQUE), gateway.applied)
    }

    @Test
    fun `a change in Android's settings while Sonora runs is stored when the screen is recreated`() = runBlocking {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        val sync = LanguageSync(preferences, gateway)
        start(sync)

        gateway.system = AppLanguage.BASQUE
        sync.adoptSystemChoice()

        assertEquals(AppLanguage.BASQUE, preferences.preferences.value.language)
    }

    @Test
    fun `a Settings choice not applied yet is not undone by a screen recreated in between`() = runBlocking {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        val sync = LanguageSync(preferences, gateway)
        val collecting = CoroutineScope(Dispatchers.Unconfined + Job())
        collecting.launch { sync.run() }
        collecting.cancel() // Castellano was applied; the next choice has not reached the collector yet

        preferences.choose(AppLanguage.ENGLISH)
        sync.adoptSystemChoice() // the system still holds Castellano, the language applied last

        assertEquals(AppLanguage.ENGLISH, preferences.preferences.value.language)
    }

    @Test
    fun `choosing the system default in Android's settings keeps Sonora's language`() = runBlocking {
        val preferences = FakePreferences(AppPreferences(language = AppLanguage.BASQUE))
        val gateway = FakeGateway()
        val sync = LanguageSync(preferences, gateway)
        start(sync)

        gateway.system = null
        sync.adoptSystemChoice()

        assertEquals(AppLanguage.BASQUE, preferences.preferences.value.language)
        assertEquals(AppLanguage.BASQUE, gateway.system)
    }

    @Test
    fun `Android versions without per-app languages apply the stored choice only`() {
        val preferences = FakePreferences()
        val gateway = FakeGateway(system = null, systemFollowsApply = false)
        val sync = LanguageSync(preferences, gateway)
        start(sync)
        preferences.choose(AppLanguage.ENGLISH)
        runBlocking { sync.adoptSystemChoice() }

        assertEquals(AppLanguage.ENGLISH, preferences.preferences.value.language)
        assertEquals(AppLanguage.ENGLISH, gateway.applied.last())
    }
}
