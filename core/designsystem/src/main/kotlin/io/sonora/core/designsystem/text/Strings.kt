package io.sonora.core.designsystem.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * String resources for copy built outside composables (pure text builders), so those builders run in JVM tests
 * against the resource files and in the app against [Resources].
 */
interface Strings {
    /** The locale the strings are in; numbers and dates are formatted for it too. */
    val locale: Locale

    fun get(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

/** [Strings] of the app's [resources], in their configuration's locale. */
class ResourceStrings(private val resources: Resources) : Strings {
    override val locale: Locale get() = resources.configuration.locales[0]

    override fun get(id: Int, vararg args: Any): String = resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
}

/** The current [Strings]; recreated when the configuration (and so the language) changes. */
@Composable
fun rememberStrings(): Strings {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(configuration, resources) { ResourceStrings(resources) }
}

/** The locale of the app's current configuration, for formatting numbers and dates in the chosen language. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
