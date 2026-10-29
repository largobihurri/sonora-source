package io.sonora.core.transcoder.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.TranscoderAudioConverter
import io.sonora.core.transcoder.android.AndroidCoverScaler
import io.sonora.core.transcoder.android.ThermalGovernor
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.ContainerMetadataChunkSource
import io.sonora.core.transcoder.metadata.CoverScaler

@Module
@InstallIn(SingletonComponent::class)
interface TranscoderModule {

    @Binds
    fun bindAudioConverter(impl: TranscoderAudioConverter): AudioConverter

    @Binds
    fun bindConversionBudgetSource(impl: ThermalGovernor): ConversionBudgetSource

    /**
     * Tags and cover go into every output in its container's own format (ID3v2.3, RIFF INFO, Vorbis comments, MP4
     * atoms), read through the domain's SourceMetadataReader.
     */
    @Binds
    fun bindMetadataChunkSource(impl: ContainerMetadataChunkSource): MetadataChunkSource

    @Binds
    fun bindCoverScaler(impl: AndroidCoverScaler): CoverScaler

    companion object {
        /** M4A outputs are muxed through a temporary file in the app's cache. */
        @Provides
        fun provideConversionEngine(
            @ApplicationContext context: Context,
            decoder: PcmDecoder,
            metadata: MetadataChunkSource,
        ): ConversionEngine = ConversionEngine(decoder, EncoderRegistry.default(tempDir = context.cacheDir), metadata)
    }
}
