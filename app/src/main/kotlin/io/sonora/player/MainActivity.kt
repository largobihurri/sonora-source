package io.sonora.player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.sonora.core.data.conversion.work.ConversionNotifications
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.layout.ProvideWindowClass
import io.sonora.core.designsystem.theme.AccentResolver
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.theme.rememberArtworkAccentSeed
import io.sonora.core.designsystem.theme.wallpaperAccent
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.playback.PlaybackService
import io.sonora.core.playback.VoicePlayRequest
import io.sonora.feature.nowplaying.LocalCastButton
import io.sonora.feature.settings.ArchiveToImport
import io.sonora.feature.settings.duplicates.DuplicateNotifications
import io.sonora.player.display.FastFrames
import io.sonora.player.display.TranslucentCeiling
import io.sonora.player.display.TranslucentLoadMonitor
import io.sonora.player.distribution.rememberCastButton
import io.sonora.player.locale.LanguageSync
import io.sonora.player.locale.LegacyLocale
import io.sonora.player.ui.AppearanceViewModel
import io.sonora.player.ui.SonoraRoot
import io.sonora.player.ui.navigationContrastEnforced
import io.sonora.player.ui.rememberTranslucentPolicy
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Set by the notification / system media controls (session activity); cleared once Now Playing opened. */
    private var openNowPlaying by mutableStateOf(false)

    /** Batch whose conversion notification was tapped; cleared once the Converter tab shows it. */
    private var openConversionBatch by mutableStateOf<Long?>(null)

    /** Set by the duplicates notification; cleared once Settings › Duplicates shows. */
    private var openDuplicates by mutableStateOf(false)

    /** A ZIP archive another app opened or shared with Sonora; cleared once the import has it. */
    private var openArchive by mutableStateOf<ArchiveToImport?>(null)

    @Inject lateinit var languageSync: LanguageSync

    /** The fastest refresh rate while something moves (see [FastFrames]). */
    private val fastFrames = FastFrames()

    /** The fullest translucency this GPU has kept up with, and what watches it draw (see [TranslucentLoadMonitor]). */
    private val translucentCeiling by lazy { TranslucentCeiling(this) }
    private val translucentLoad by lazy { TranslucentLoadMonitor(window) { drawn -> translucentCeiling.lower(drawn) } }

    /** Before Android 13 the activity's context carries Sonora's language itself (see [LegacyLocale]). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) LegacyLocale.wrap(newBase) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        orientation = resources.configuration.orientation
        followLanguage()
        // A recreated activity keeps its launch intent; only a fresh launch asks for Now Playing.
        if (savedInstanceState == null) {
            openNowPlaying = intent.opensNowPlaying()
            openConversionBatch = intent.conversionBatch()
            openArchive = intent.archiveToImport()
            openDuplicates = intent.opensDuplicates()
            if (VoicePlayRequest.handles(intent)) VoicePlayRequest.play(this, intent)
        }
        setContent {
            // While something moves, the whole UI votes for the high frame rate category (see FastFrames).
            Box(Modifier.preferredFrameRate(if (fastFrames.fast) FrameRateCategory.High else FrameRateCategory.Default)) {
                // The window's class (phone or tablet, orientation), read by every screen through LocalWindowClass.
                ProvideWindowClass {
                    AppTheme {
                        // Now Playing's Cast slot, when the build and the device can cast (the `full` build).
                        CompositionLocalProvider(LocalCastButton provides rememberCastButton()) {
                            SonoraRoot(
                                openNowPlaying = openNowPlaying,
                                onNowPlayingOpened = { openNowPlaying = false },
                                openConversionBatch = openConversionBatch,
                                onConversionBatchOpened = { openConversionBatch = null },
                                openArchive = openArchive,
                                onArchiveOpened = { openArchive = null },
                                openDuplicates = openDuplicates,
                                onDuplicatesOpened = { openDuplicates = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        fastFrames.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    /** The orientation last seen; the resources already carry the new one when [onConfigurationChanged] runs. */
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    /** The activity handles rotations itself (manifest configChanges); a turn animates at the fastest rate. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation != orientation) fastFrames.onTurn()
        orientation = newConfig.orientation
        super.onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        fastFrames.release()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.opensNowPlaying()) openNowPlaying = true
        intent.conversionBatch()?.let { openConversionBatch = it }
        intent.archiveToImport()?.let { openArchive = it }
        if (intent.opensDuplicates()) openDuplicates = true
        if (VoicePlayRequest.handles(intent)) VoicePlayRequest.play(this, intent)
    }

    /**
     * A language picked in Android's app language settings recreates this activity: it becomes the stored choice.
     * Before Android 13 a new language recreates the activity here; the tab and screen state are saved and playback
     * runs in its service, so neither is lost.
     */
    private fun followLanguage() {
        lifecycleScope.launch { languageSync.adoptSystemChoice() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val createdIn = LegacyLocale.language.value
            lifecycleScope.launch {
                LegacyLocale.language.first { it != createdIn }
                recreate()
            }
        }
    }

    private fun Intent.opensNowPlaying() = getBooleanExtra(PlaybackService.EXTRA_OPEN_NOW_PLAYING, false)

    private fun Intent.opensDuplicates() = getBooleanExtra(DuplicateNotifications.EXTRA_OPEN_DUPLICATES, false)

    /**
     * The archive of "Open with Sonora" (ACTION_VIEW) or "Share to Sonora" (ACTION_SEND), a content URI only. The
     * sender's read grant lasts while this activity does; a persistable one is kept when the sender allows it.
     */
    private fun Intent.archiveToImport(): ArchiveToImport? {
        val uri = when (action) {
            Intent.ACTION_VIEW -> data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return ArchiveToImport(uri, persistable = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    private fun Intent.conversionBatch(): Long? =
        getLongExtra(ConversionNotifications.EXTRA_OPEN_CONVERSION_BATCH, NO_BATCH).takeIf { it != NO_BATCH }

    /**
     * The user's theme and accent around [content]; nothing is drawn until the settings are read (a few
     * milliseconds), so a dark choice never flashes light first. System bar icons follow the app theme. The
     * Translucency switch applies live through [LocalTranslucentPolicy]; translucency the GPU does not keep up with
     * steps down on its own, and turning the switch back on gives the full translucency another try.
     */
    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val viewModel: AppearanceViewModel = hiltViewModel()
        val appearance by viewModel.appearance.collectAsStateWithLifecycle()
        val artworkUri by viewModel.artworkUri.collectAsStateWithLifecycle()
        val look = appearance ?: return
        val dark = look.theme.isDark(isSystemInDarkTheme())
        val context = LocalContext.current
        val seed = rememberArtworkAccentSeed(artworkUri.takeIf { look.accentMode == AccentMode.ARTWORK })
        val accent = remember(look, seed, context) {
            AccentResolver.resolve(look, Build.VERSION.SDK_INT, { wallpaperAccent(context, it) }, seed)
        }
        val windowClass = LocalWindowClass.current
        LaunchedEffect(dark, windowClass) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { dark },
            )
            // enableEdgeToEdge turns the contrast scrim on; a large screen has none (see navigationContrastEnforced).
            window.isNavigationBarContrastEnforced = navigationContrastEnforced(windowClass)
        }
        var switchWasOn by remember { mutableStateOf(look.translucency) }
        LaunchedEffect(look.translucency) {
            if (look.translucency && !switchWasOn) translucentCeiling.clear()
            switchWasOn = look.translucency
        }
        val translucent = rememberTranslucentPolicy(look.translucency, translucentCeiling.tier)
        DisposableEffect(translucent.tier) {
            translucentLoad.watch(translucent.tier)
            onDispose { translucentLoad.stop() }
        }
        SonoraTheme(darkTheme = dark, accent = accent) {
            CompositionLocalProvider(LocalTranslucentPolicy provides translucent, content = content)
        }
    }

    private companion object {
        const val NO_BATCH = -1L

        // The scrims enableEdgeToEdge() uses by default behind three-button navigation.
        val LIGHT_NAVIGATION_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
