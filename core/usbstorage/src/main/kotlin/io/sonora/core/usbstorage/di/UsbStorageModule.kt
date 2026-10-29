package io.sonora.core.usbstorage.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.storage.UsbStorageGateway
import io.sonora.core.usbstorage.android.AndroidUsbStorageGateway
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher USB block I/O runs on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UsbStorageDispatcher

@Module
@InstallIn(SingletonComponent::class)
interface UsbStorageModule {
    @Binds
    fun bindUsbStorageGateway(impl: AndroidUsbStorageGateway): UsbStorageGateway

    companion object {
        @Provides
        @UsbStorageDispatcher
        fun provideUsbStorageDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
