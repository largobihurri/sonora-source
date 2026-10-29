package io.sonora.core.playback.equalizer

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.playback.ReplayGain
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.domain.playback.ReplayGainTags
import io.sonora.core.playback.crossfade.FadeGain
import io.sonora.core.playback.levels.BandLevelMeter
import io.sonora.core.playback.output.BitPerfectGate
import io.sonora.core.playback.replaygain.QueueAlbumOrder
import io.sonora.core.playback.replaygain.ReplayGainMetadata
import java.nio.ByteBuffer

/**
 * Runs [processor] on the PCM the audio renderers hand to [sink], ahead of the sink's own processing.
 *
 * Why here and not in the sink's processor chain: with float output on, Media3's DefaultAudioSink sends
 * high-resolution PCM (24/32-bit, float, which is also what MediaCodec decoders deliver) straight to its float
 * conversion and skips custom processors, so an equalizer there would only ever hear 16-bit files.
 *
 * Each stream's encoding is chosen when the sink is configured: float while the equalizer or ReplayGain is on
 * (filtered or scaled audio needs the headroom), the stream's own encoding while both are off, so a 16-bit file keeps
 * a 16-bit AudioTrack. A later switch never reconfigures the sink: switching on mid-stream filters within the
 * stream's encoding (with dither), and switching off hands the stream's own buffers straight to [sink] once the curve
 * has faded out.
 *
 * ReplayGain ([replayGain]): each stream's tags come with its format ([ReplayGainMetadata]) and, for
 * [ReplayGainMode.AUTO], whether it plays as part of an album in order comes from the queue the format's config
 * carries ([QueueAlbumOrder]). The processor's input gain follows them and the latest settings, read at every buffer.
 * A stream starting after a flush (a new start or a seek) takes its gain at once; the next song of a gapless
 * sequence (configured with no flush, in the same format, so neither the processor nor the sink restarts) ramps to
 * its own gain over a few milliseconds instead of stepping.
 *
 * [meter] (when given) measures each buffer exactly as [sink] gets it (after the equalizer, or the stream's own buffer
 * in bypass), once per buffer, stamped with its presentation time; the position [sink] reports and its play/pause
 * state tell the meter's readers which of those buffers is being heard. Streams that are not PCM go unmeasured.
 *
 * [bitPerfect] (when given) hears each stream's format first and, while it is armed, switches the equalizer and ReplayGain
 * off whatever the settings say (a stream is then handed on in its own encoding, untouched), so the bit-perfect output
 * receives the file's samples as they are.
 *
 * [fadeGain] (when given) is the crossfade level: [sink] gets the player's volume times that level, updated with each
 * buffer it is handed. The AudioTrack applies its volume as the audio is played out, so the level is heard at once.
 */
@UnstableApi
class EqualizerAudioSink(
    sink: AudioSink,
    private val processor: EqualizerAudioProcessor,
    private val settings: () -> EqualizerSettings,
    private val meter: BandLevelMeter? = null,
    private val replayGain: () -> ReplayGainSettings = { ReplayGainSettings() },
    private val fadeGain: FadeGain? = null,
    private val bitPerfect: BitPerfectGate? = null,
) : ForwardingAudioSink(sink) {

    /** Whether the configured stream is PCM the processor handles. */
    private var processing = false
    private var configuredInput: AudioProcessor.AudioFormat? = null
    private var configuredFloat = false

    /** The buffer [sink] has been given but not yet taken entirely; the renderer re-sends its buffer until then. */
    private var pending: ByteBuffer? = null
    private var passthroughPending = false

    /** The configured stream's ReplayGain tags and queue context, and the settings its gain was last set from. */
    private var streamTags = ReplayGainTags.NONE
    private var streamAlbumInOrder = false
    private var gainSettings: ReplayGainSettings? = null

    /** Whether no audio has gone through since a flush or reset: the next stream's gain applies at once. */
    private var fromSilence = true

    /** The player's own volume, and the fade level last applied on top of it. */
    private var playerVolume = 1f
    private var appliedFade = 1f

    override fun configure(config: AudioSink.AudioSinkConfig) {
        clearPending()
        val format = config.format
        bitPerfect?.streamStarting(format)
        val input = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) AudioProcessor.AudioFormat(format) else null
        // Armed bit-perfect output: nothing touches the stream, which goes on in its own encoding. The next stream
        // after it starts the filters clean.
        val untouched = bitPerfect?.armed == true
        if (untouched) configuredInput = null
        processing = !untouched && input != null && EqualizerAudioProcessor.bytesPerSample(input.encoding) > 0
        if (!processing || input == null) {
            meter?.configure(format.sampleRate, format.channelCount, C.ENCODING_INVALID)
            super.configure(config)
            return
        }
        val replayGainSettings = replayGain()
        val floatOutput = settings().enabled || replayGainSettings.mode != ReplayGainMode.OFF
        // A gapless track in the same format keeps the filters running instead of restarting them.
        val restarted = input != configuredInput || floatOutput != configuredFloat
        if (restarted) {
            processor.floatOutput = floatOutput
            processor.configure(input)
            processor.flush()
            configuredInput = input
            configuredFloat = floatOutput
        }
        streamTags = ReplayGainMetadata.tags(format.metadata)
        streamAlbumInOrder = QueueAlbumOrder.albumInOrder(config.timeline, config.mediaPeriodId)
        gainSettings = null
        updateGain(replayGainSettings, immediate = restarted || fromSilence)
        val encoding = if (floatOutput) C.ENCODING_PCM_FLOAT else input.encoding
        meter?.configure(input.sampleRate, input.channelCount, encoding)
        if (encoding == format.pcmEncoding) {
            super.configure(config)
        } else {
            super.configure(
                AudioSink.AudioSinkConfig.Builder(format.buildUpon().setPcmEncoding(encoding).build())
                    .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
                    .setOutputChannelMapping(config.outputChannelMapping)
                    .setTimeline(config.timeline)
                    .setMediaPeriodId(config.mediaPeriodId)
                    .build(),
            )
        }
    }

    override fun setVolume(volume: Float) {
        playerVolume = volume
        super.setVolume(volume * appliedFade)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        fadeGain?.gain?.let { fade ->
            if (fade != appliedFade) {
                appliedFade = fade
                super.setVolume(playerVolume * fade)
            }
        }
        if (!processing) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        fromSilence = false
        if (pending == null && !passthroughPending) updateGain(replayGain(), immediate = false)
        // The sink must get back the very buffer it did not finish, whatever the settings say now. A buffer passed
        // straight on never reaches the processor, which isPassthrough() accounts for (its filters restart clean).
        if (passthroughPending || (pending == null && processor.isPassthrough())) {
            if (!passthroughPending) meter?.process(buffer, presentationTimeUs)
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            passthroughPending = !done
            return done
        }
        val output = pending ?: run {
            processor.queueInput(buffer)
            processor.output.also { meter?.process(it, presentationTimeUs) }
        }
        val done = super.handleBuffer(output, presentationTimeUs, encodedAccessUnitCount)
        pending = if (done) null else output
        return done
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also { if (it != AudioSink.CURRENT_POSITION_NOT_SET) meter?.played(it) }

    override fun play() {
        super.play()
        meter?.running(true)
    }

    override fun pause() {
        meter?.running(false)
        super.pause()
    }

    override fun flush() {
        clearPending()
        fromSilence = true
        meter?.flush()
        if (configuredInput != null) processor.flush()
        super.flush()
    }

    override fun reset() {
        clearPending()
        meter?.running(false)
        meter?.flush()
        processor.reset()
        configuredInput = null
        processing = false
        gainSettings = null
        fromSilence = true
        super.reset()
    }

    /** Points the processor's input gain at the configured stream's gain under [latest] (when they changed). */
    private fun updateGain(latest: ReplayGainSettings, immediate: Boolean) {
        if (!immediate && latest === gainSettings) return
        gainSettings = latest
        processor.setInputGain(ReplayGain.gain(streamTags, latest, streamAlbumInOrder), immediate)
    }

    private fun clearPending() {
        pending = null
        passthroughPending = false
    }
}
