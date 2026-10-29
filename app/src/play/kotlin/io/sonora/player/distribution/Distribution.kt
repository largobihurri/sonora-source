package io.sonora.player.distribution

import android.app.Application
import androidx.compose.runtime.Composable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.watchport.UnavailableWatchSync
import io.sonora.core.watchport.WatchSyncController
import io.sonora.feature.nowplaying.CastButton

/**
 * What differs between the two builds (see app/build.gradle.kts): the `play` build, sold on Google Play, carries no
 * Google Play services, so it has no Chromecast (no session router is bound, the session stays on the phone) and no
 * watch link (the screens offer nothing about the watch); it also has no online lyrics search (no online lyrics source
 * is bound) and so no network access at all.
 */
internal object Distribution {
    fun onCreate(app: Application) = Unit
}

/** No Cast slot in Now Playing. */
@Composable
internal fun rememberCastButton(): CastButton? = null

@Module
@InstallIn(SingletonComponent::class)
internal object PlayDistributionModule {
    @Provides
    fun provideWatchSync(): WatchSyncController = UnavailableWatchSync
}
