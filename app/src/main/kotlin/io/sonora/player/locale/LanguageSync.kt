package io.sonora.player.locale

import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.port.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Where the app's language is applied: Android's per-app language (13+) or Sonora's own override before that. */
interface AppLocaleGateway {
    /**
     * The language Android holds for Sonora, picked in Sonora's Settings or in Android's app language settings;
     * `null` when it holds none ("System default") or one Sonora is not translated into, and always before Android 13.
     */
    fun systemChoice(): AppLanguage?

    /** Makes [language] the app's language, recreating the visible screen if it changes. Applying it twice is harmless. */
    fun apply(language: AppLanguage)
}

/**
 * Keeps the app's language and the stored choice in step. The stored choice (Castellano until the user picks another)
 * is applied at start and whenever it changes; a language picked in Android's own app language settings is taken over
 * into the stored choice, so both screens always agree.
 */
@Singleton
class LanguageSync @Inject constructor(
    private val preferences: PreferencesRepository,
    private val gateway: AppLocaleGateway,
) {
    /** The language this process applied last; `null` until the first is applied. */
    @Volatile private var applied: AppLanguage? = null

    /** Applies the stored choice now and on every change; runs for the lifetime of the process. */
    suspend fun run() {
        adoptSystemChoice()
        preferences.preferences.map { it.language }.distinctUntilChanged().collect { language ->
            gateway.apply(language)
            applied = language
        }
    }

    /**
     * Called when a screen is created: a language Android holds that differs from the one applied last came from
     * Android's settings, so it becomes the stored choice. "System default" is not one of Sonora's choices, so the
     * language applied last stays.
     */
    suspend fun adoptSystemChoice() {
        val system = gateway.systemChoice()
        val last = applied
        when {
            system == null -> last?.let(gateway::apply)
            system != last -> preferences.update { if (it.language == system) it else it.copy(language = system) }
        }
    }
}
