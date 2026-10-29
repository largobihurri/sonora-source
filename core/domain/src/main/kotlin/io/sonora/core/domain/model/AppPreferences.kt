package io.sonora.core.domain.model

import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets

/**
 * The user's app settings. Grouped by screen so each group can grow on its own; every field has the default a fresh
 * install uses.
 */
data class AppPreferences(
    val nowPlaying: NowPlayingPreferences = NowPlayingPreferences(),
    val appearance: AppearancePreferences = AppearancePreferences(),
    val audio: AudioPreferences = AudioPreferences(),
    /** The app's language; Castellano until the user picks another, whatever the device language is. */
    val language: AppLanguage = AppLanguage.SPANISH,
    val navigation: NavigationPreferences = NavigationPreferences(),
    /**
     * Whether other apps and the adb shell may control Sonora through its external API (see `core:api`). Off until the
     * user turns it on; while off, every external caller is denied. Sonora's own uid is not affected.
     */
    val externalControlEnabled: Boolean = false,
    val converter: ConverterPreferences = ConverterPreferences(),
)

/** The converter's settings kept between batches; the choices for single songs last for their batch only. */
data class ConverterPreferences(
    /**
     * The batch-wide output a new batch starts on, as a [io.sonora.core.domain.conversion.ConversionProfile.id]: the
     * XDJ-700 until the user picks another.
     */
    val outputProfileId: String = ConversionProfiles.XDJ_700.id,
) {
    /** [outputProfileId] as a target to pick; the XDJ-700 when it names none (unknown, or not a user's choice). */
    val outputTarget: OutputTarget get() = OutputTargets.parse(outputProfileId) ?: OutputTarget.Xdj700

    companion object {
        fun of(target: OutputTarget): ConverterPreferences = ConverterPreferences(outputProfileId = target.id)
    }
}

/** How the app shell navigates on large screens (tablets). Phones ignore it. */
data class NavigationPreferences(
    /**
     * Whether the translucent sidebar is shown on a large screen; hidden, the main sections sit in a pill at the top of the
     * content instead. Shown until the user hides it; kept per device.
     */
    val sidebarShown: Boolean = true,
)

/** The languages Sonora is translated into, in the order Settings lists them. Names are persisted: never rename one. */
enum class AppLanguage(
    /** BCP 47 language tag of the translation. */
    val tag: String,
) {
    SPANISH("es"),
    ENGLISH("en"),
    BASQUE("eu"),
    ;

    companion object {
        /** The language whose tag is the primary subtag of [tag] (`es-ES` is Castellano); `null` for any other. */
        fun fromTag(tag: String?): AppLanguage? {
            val primary = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
            return entries.firstOrNull { it.tag == primary }
        }
    }
}

/** Which optional buttons the Now Playing screen shows, what it shows turned sideways, and whether it hangs level. */
data class NowPlayingPreferences(
    /** Lyrics are not available yet, so the button is hidden until the user asks for it. */
    val showLyricsButton: Boolean = false,
    val showFileInfoButton: Boolean = true,
    val rotation: NowPlayingRotation = NowPlayingRotation.NOW_PLAYING_LANDSCAPE,
    /**
     * Gravity mode: Now Playing stays portrait and its two groups (artwork, controls) each turn about their own centre
     * to stay level as the phone tilts. Off until the user turns it on; while on, [rotation] does not apply.
     */
    val gravity: Boolean = false,
)

/** How the sound leaves the phone. */
data class AudioPreferences(
    /**
     * Bit-perfect USB output (Android 14+): while a USB audio device that offers it is the one heard, the player sends
     * each song's samples unchanged at their own rate and bit depth, and every effect that would change them (equalizer,
     * volume normalisation, crossfade, speed, pitch) is off. Off until the user turns it on.
     */
    val bitPerfectUsb: Boolean = false,
)

/** What an open Now Playing shows when the phone turns sideways. Names are persisted: never rename one. */
enum class NowPlayingRotation {
    /** Now Playing's own landscape layout. */
    NOW_PLAYING_LANDSCAPE,

    /** The albums Cover Flow, opened on the album playing; upright again, Now Playing returns. */
    ALBUM_COVER_FLOW,
}

/** How the app looks: light or dark, where its accent colour comes from, and how its floating chrome is drawn. */
data class AppearancePreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentMode: AccentMode = AccentMode.MATERIAL_YOU,
    /** The colour of [AccentMode.PRESET], also used by [AccentMode.ARTWORK] while the artwork gives no colour. */
    val preset: AccentPreset = AccentPreset.PINK,
    /**
     * iOS 26 style translucency (refraction, blur and a specular rim) on the floating chrome; off draws the flat
     * translucent surface. Android versions without the needed graphics fall back on their own.
     */
    val translucency: Boolean = true,
)

/** Light or dark appearance. Dark uses true black backgrounds. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    /** Whether the app is dark while the device's own theme is dark ([systemDark]) or light. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/** Where the accent colour comes from. */
enum class AccentMode {
    /** The wallpaper colours (Android 12+); Sonora pink on older versions. */
    MATERIAL_YOU,

    /** One of the [AccentPreset] colours. */
    PRESET,

    /** The artwork of the song that is playing. */
    ARTWORK,
}

/** The curated accent colours, in the order the palette shows them. Names are persisted: never rename one. */
enum class AccentPreset { RED, ORANGE, AMBER, GREEN, TEAL, BLUE, INDIGO, PURPLE, PINK }
