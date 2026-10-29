package io.sonora.core.playback.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3's default extractors with [AiffExtractor] in front. It goes first because its sniff is exact (the 12-byte
 * FORM/AIFF header) while some default extractors sniff loosely through the first bytes, and AIFF files carry ID3
 * chunks and PCM data that could look like a frame to them. Configuration calls from the media source factory reach
 * the defaults.
 */
@UnstableApi
internal class SonoraExtractorsFactory(
    private val defaults: DefaultExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors(uri, responseHeaders)

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        defaults.setSubtitleParserFactory(subtitleParserFactory)
    }

    @OptIn(ExperimentalApi::class)
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        defaults.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    @OptIn(ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
        apply { defaults.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }
}
