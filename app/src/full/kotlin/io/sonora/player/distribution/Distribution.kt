package io.sonora.player.distribution

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.cast.CastRouting
import io.sonora.core.cast.CastSupport
import io.sonora.core.cast.ui.CastRouteButton
import io.sonora.core.data.lyrics.lrclib.LrclibSource
import io.sonora.core.domain.port.OnlineLyricsSource
import io.sonora.core.playback.RoutedPlayer
import io.sonora.core.playback.SessionRouter
import io.sonora.feature.nowplaying.CastButton
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What differs between the two builds (see app/build.gradle.kts): the `full` build, with Chromecast, the watch link and
 * the online lyrics search.
 */
internal object Distribution {
    /**
     * Loads the Cast context (Default Media Receiver) before any Cast button or player needs it; a no-op without Google
     * Play services, where casting is hidden. The watch link needs nothing here: its Hilt bindings come with
     * :core:watchphone.
     */
    fun onCreate(app: Application) = CastSupport.initialize(app)
}

/** Now Playing's Cast slot: the route chooser on a device that can cast, else none. */
@Composable
internal fun rememberCastButton(): CastButton? {
    val context = LocalContext.current
    return remember(context) { CastRoute.takeIf { CastSupport.isSupported(context) } }
}

private val CastRoute: CastButton = { tint, modifier -> CastRouteButton(tint = tint, modifier = modifier) }

/** The session moves to a Cast device and back through [CastRouting] (B12). */
internal class CastSessionRouter @Inject constructor() : SessionRouter {
    override fun route(context: Context, local: Player): RoutedPlayer? = CastRouting.create(context, local)?.let { casting ->
        object : RoutedPlayer {
            override val player: Player = casting.player

            override fun release() = casting.release()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface FullDistributionModule {
    @Binds
    fun bindSessionRouter(impl: CastSessionRouter): SessionRouter
}

/** The online lyrics search (LRCLIB): `full` only, since the lyrics it serves carry no publisher licence. */
@Module
@InstallIn(SingletonComponent::class)
internal object FullLyricsModule {
    @Provides
    @Singleton
    fun provideOnlineLyricsSource(@ApplicationContext context: Context): OnlineLyricsSource = LrclibSource.create(context)
}
