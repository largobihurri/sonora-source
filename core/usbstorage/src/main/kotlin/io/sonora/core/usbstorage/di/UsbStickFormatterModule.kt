package io.sonora.core.usbstorage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.storage.UsbStickFormatter
import io.sonora.core.fat.FatUsbStickFormatter

/**
 * Binds the formatter here because `core:fat` is a plain JVM module without Hilt, and the stick it formats is always
 * one this module opened.
 */
@Module
@InstallIn(SingletonComponent::class)
object UsbStickFormatterModule {
    @Provides
    fun provideUsbStickFormatter(): UsbStickFormatter = FatUsbStickFormatter()
}
