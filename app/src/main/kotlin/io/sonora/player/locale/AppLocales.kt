package io.sonora.player.locale

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import io.sonora.core.domain.model.AppLanguage
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android 13+: the system's per-app language, which also feeds Android's own app language settings. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LocaleManagerGateway(context: Context) : AppLocaleGateway {
    private val localeManager = context.getSystemService(LocaleManager::class.java)

    override fun systemChoice(): AppLanguage? =
        localeManager.applicationLocales.takeUnless { it.isEmpty }?.let { AppLanguage.fromTag(it[0].toLanguageTag()) }

    override fun apply(language: AppLanguage) {
        val current = localeManager.applicationLocales
        if (current.isEmpty || AppLanguage.fromTag(current[0].toLanguageTag()) != language) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }
}

/**
 * Android 10-12, which have no per-app language: Sonora overrides the locale of its application resources and wraps
 * each activity's context ([LegacyLocale.wrap]); a change recreates the activity through [LegacyLocale.language].
 */
class LegacyLocaleGateway(private val application: Application) : AppLocaleGateway {
    override fun systemChoice(): AppLanguage? = null

    override fun apply(language: AppLanguage) {
        if (LegacyLocale.language.value == language && Locale.getDefault().language == language.tag) return
        LegacyLocale.set(language)
        LegacyLocale.applyTo(application)
    }
}

/** The language of Sonora's own override before Android 13; Castellano until the stored choice is read. */
object LegacyLocale {
    private val current = MutableStateFlow(AppLanguage.SPANISH)

    /** Activities recreate themselves when this differs from the language they were created in. */
    val language: StateFlow<AppLanguage> = current.asStateFlow()

    fun set(language: AppLanguage) {
        Locale.setDefault(Locale.forLanguageTag(language.tag))
        current.value = language
    }

    /** [base] with the configuration's locale replaced by the current language; for `attachBaseContext`. */
    fun wrap(base: Context): Context = base.createConfigurationContext(localized(base.resources.configuration))

    /** Re-applies the language to the application's resources, which Android resets on every configuration change. */
    fun applyTo(application: Application) {
        val resources = application.resources
        @Suppress("DEPRECATION") // the only way to localize the application context before Android 13
        resources.updateConfiguration(localized(resources.configuration), resources.displayMetrics)
    }

    private fun localized(configuration: Configuration) = Configuration(configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(current.value.tag)))
    }
}
