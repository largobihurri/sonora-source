package io.sonora.core.decoder.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.decoder.AndroidPcmDecoder
import io.sonora.core.domain.audio.PcmDecoder
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher PCM decoding (file reads, MediaCodec calls) runs on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DecoderDispatcher

@Module
@InstallIn(SingletonComponent::class)
interface DecoderModule {
    @Binds
    fun bindPcmDecoder(impl: AndroidPcmDecoder): PcmDecoder

    companion object {
        @Provides
        @DecoderDispatcher
        fun provideDecoderDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
