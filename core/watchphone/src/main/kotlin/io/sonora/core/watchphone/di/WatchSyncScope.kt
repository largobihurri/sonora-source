package io.sonora.core.watchphone.di

import javax.inject.Qualifier

/** The application-lifetime scope a send to the watch runs on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WatchSyncScope
