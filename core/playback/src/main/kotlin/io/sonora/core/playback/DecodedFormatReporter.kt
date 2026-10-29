package io.sonora.core.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.PcmEncoding

/**
 * Follows what the session's player does with each queue entry's audio: the format the audio renderer receives
 * (compressed, or PCM in its stored encoding) and the encoding and rate the AudioTrack was opened with. [publish]
 * gets the formats of the last few entries whenever one changes; the service sends them to controllers as session
 * extras ([DecodedFormatExtras]).
 *
 * Entries are told apart by the queue item id in their media id ([QueueMediaId]). An entry that plays through the
 * AudioTrack of the one before it (same output format, gapless) inherits that AudioTrack's format, since the sink
 * reopens the track (and reports it) only when the output format changes.
 */
@UnstableApi
internal class DecodedFormatReporter(private val publish: (List<DecodedAudioFormat>) -> Unit) : AnalyticsListener {

    private val formats = LinkedHashMap<Long, DecodedAudioFormat>()
    private var lastOutput: AudioSink.AudioTrackConfig? = null
    private val window = Timeline.Window()

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        val itemId = itemId(eventTime) ?: return
        update(itemId) {
            it.copy(
                inputMimeType = format.sampleMimeType,
                inputEncoding = DecodedFormatExtras.encodingOf(format.pcmEncoding),
                inputSampleRateHz = format.sampleRate.takeIf { rate -> rate > 0 },
                channelCount = format.channelCount.takeIf { count -> count > 0 },
                outputEncoding = it.outputEncoding ?: lastOutput?.let { output -> DecodedFormatExtras.encodingOf(output.encoding) },
                outputSampleRateHz = it.outputSampleRateHz ?: lastOutput?.sampleRate,
            )
        }
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        lastOutput = audioTrackConfig
        val itemId = itemId(eventTime) ?: return
        update(itemId) {
            it.copy(
                outputEncoding = DecodedFormatExtras.encodingOf(audioTrackConfig.encoding),
                outputSampleRateHz = audioTrackConfig.sampleRate,
            )
        }
    }

    private fun update(itemId: Long, change: (DecodedAudioFormat) -> DecodedAudioFormat) {
        val current = formats.remove(itemId) ?: DecodedAudioFormat(itemId, null, null, null, null, null, null)
        formats[itemId] = change(current)
        while (formats.size > KEPT_ENTRIES) formats.remove(formats.keys.first())
        publish(formats.values.toList())
    }

    /** The queue entry of the period the event is about. */
    private fun itemId(eventTime: AnalyticsListener.EventTime): Long? {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return null
        val mediaId = timeline.getWindow(eventTime.windowIndex, window).mediaItem.mediaId
        return QueueMediaId.parse(mediaId).itemId
    }

    private companion object {
        /** The current entry, the one before it (still draining at a transition) and the next one (read ahead). */
        const val KEPT_ENTRIES = 4
    }
}

/** [DecodedAudioFormat]s as session extras, and Media3 PCM encodings as the domain's [PcmEncoding]. */
internal object DecodedFormatExtras {
    private const val PREFIX = "io.sonora.decoded."
    private const val ITEM_IDS = PREFIX + "itemIds"
    private const val INPUT_MIME_TYPES = PREFIX + "inputMimeTypes"
    private const val INPUT_ENCODINGS = PREFIX + "inputEncodings"
    private const val INPUT_RATES = PREFIX + "inputRates"
    private const val CHANNELS = PREFIX + "channels"
    private const val OUTPUT_ENCODINGS = PREFIX + "outputEncodings"
    private const val OUTPUT_RATES = PREFIX + "outputRates"

    /** Stands for `null` in the parallel arrays. */
    private const val NONE = -1

    fun toBundle(formats: List<DecodedAudioFormat>): Bundle = Bundle().apply {
        putLongArray(ITEM_IDS, formats.map { it.queueItemId }.toLongArray())
        putStringArray(INPUT_MIME_TYPES, formats.map { it.inputMimeType.orEmpty() }.toTypedArray())
        putIntArray(INPUT_ENCODINGS, formats.map { it.inputEncoding?.ordinal ?: NONE }.toIntArray())
        putIntArray(INPUT_RATES, formats.map { it.inputSampleRateHz ?: NONE }.toIntArray())
        putIntArray(CHANNELS, formats.map { it.channelCount ?: NONE }.toIntArray())
        putIntArray(OUTPUT_ENCODINGS, formats.map { it.outputEncoding?.ordinal ?: NONE }.toIntArray())
        putIntArray(OUTPUT_RATES, formats.map { it.outputSampleRateHz ?: NONE }.toIntArray())
    }

    /** The formats in [extras]; empty when it holds none or arrays that do not line up. */
    fun fromBundle(extras: Bundle): List<DecodedAudioFormat> {
        val ids = extras.getLongArray(ITEM_IDS) ?: return emptyList()
        val mimeTypes = extras.getStringArray(INPUT_MIME_TYPES)
        val arrays = listOf(INPUT_ENCODINGS, INPUT_RATES, CHANNELS, OUTPUT_ENCODINGS, OUTPUT_RATES).map { extras.getIntArray(it) }
        if (mimeTypes?.size != ids.size || arrays.any { it?.size != ids.size }) return emptyList()
        val (inputEncodings, inputRates, channels, outputEncodings, outputRates) = arrays.map { checkNotNull(it) }
        return ids.indices.map { i ->
            DecodedAudioFormat(
                queueItemId = ids[i],
                inputMimeType = mimeTypes[i].takeIf { it.isNotEmpty() },
                inputEncoding = PcmEncoding.entries.getOrNull(inputEncodings[i]),
                inputSampleRateHz = inputRates[i].takeIf { it != NONE },
                channelCount = channels[i].takeIf { it != NONE },
                outputEncoding = PcmEncoding.entries.getOrNull(outputEncodings[i]),
                outputSampleRateHz = outputRates[i].takeIf { it != NONE },
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun encodingOf(encoding: Int): PcmEncoding? = when (encoding) {
        C.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8
        C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> PcmEncoding.PCM_16_BIG_ENDIAN
        C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> PcmEncoding.PCM_24_BIG_ENDIAN
        C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32
        C.ENCODING_PCM_32BIT_BIG_ENDIAN -> PcmEncoding.PCM_32_BIG_ENDIAN
        C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> PcmEncoding.FLOAT_32_BIG_ENDIAN
        C.ENCODING_PCM_DOUBLE -> PcmEncoding.FLOAT_64
        C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> PcmEncoding.FLOAT_64_BIG_ENDIAN
        else -> null
    }
}
