package io.sonora.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.dub.DataStoreDubSetupRepository
import io.sonora.core.data.dub.SharedPreferencesDubModeSettings
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.dubmodel.DubAvailability
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.UnavailableDubMode
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * DUB mode: its flag (a preference file of its own, like DJ mode's, and not part of Sonora's settings backup) and its
 * effect setup (a DataStore file of its own, like the equalizer's).
 */
@Module
@InstallIn(SingletonComponent::class)
object DubModule {
    // The file name is persisted: never rename it.
    internal const val SETUP_FILE_NAME = "sonora_dub_setup"

    /**
     * The DUB layout is exclusive with the DJ layout: turning DUB on turns DJ off once DUB is on, so a switch never
     * reads both off (DJ mode's own binding does the reverse). Both found stored on (a crash between the two writes)
     * resolve to DJ on the first read. [Lazy] breaks the cycle between the two flags. A build that does not offer DUB
     * mode ([DubAvailability], bound by the app's build flavor) gets [UnavailableDubMode], always off.
     */
    @Provides
    @Singleton
    fun provideDubModeSettings(
        @ApplicationContext context: Context,
        dj: Lazy<DjModeSettings>,
        availability: DubAvailability,
    ): DubModeSettings = if (!availability.offered) {
        UnavailableDubMode
    } else {
        SharedPreferencesDubModeSettings(
            context.getSharedPreferences(SharedPreferencesDubModeSettings.FILE_NAME, Context.MODE_PRIVATE),
            otherLayoutOn = { dj.get().enabled.value },
            turnOtherLayoutOff = { dj.get().setEnabled(false) },
        )
    }

    @Provides
    @Singleton
    @DubSetupSettingsStore
    fun provideDubSetupDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(SETUP_FILE_NAME) }

    @Provides
    @Singleton
    fun provideDubSetupRepository(@DubSetupSettingsStore store: DataStore<Preferences>): DubSetupRepository =
        DataStoreDubSetupRepository(store)
}

/** The DUB effect setup's DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DubSetupSettingsStore
