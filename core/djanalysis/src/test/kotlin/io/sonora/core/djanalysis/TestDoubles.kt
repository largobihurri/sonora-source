package io.sonora.core.djanalysis

import io.sonora.core.analysis.AnalysisEngine
import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.analysis.EngineResult
import io.sonora.core.djengine.pipeline.PcmSource
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCueStore
import io.sonora.core.djmodel.DjCues
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djmodel.GridEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

fun audioId(n: Int): AudioId = AudioId.of(AudioId.PAYLOAD_V1, n.toString(16).padStart(64, '0'))

fun waveform(columns: Int, level: Int = 100): BandWaveform {
    val plane = ByteArray(columns) { level.toByte() }
    return BandWaveform(plane, plane, plane, plane, version = 1)
}

fun analysis(
    waveform: BandWaveform = waveform(150),
    durationMs: Long = 1_000,
    grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(100.0, 128.0, 1))),
): DjAnalysis = DjAnalysis(
    durationMs = durationMs,
    grid = grid,
    key = KeyEstimate(MusicalKey.fromIndex(1)!!, 0.8, "edma"),
    waveform = waveform,
    provenance = AnalysisProvenance(1, BeatSource.MODEL, "beatthis-small0/v", Accelerator.CPU, 1_700_000_000_000),
)

/** Interleaved samples at a rate, read in blocks of at most [framesPerRead]. */
class ArrayPcmSource(
    private val samples: FloatArray,
    override val sampleRateHz: Int,
    override val channelCount: Int,
    override val totalFrames: Long? = (samples.size / channelCount).toLong(),
    private val framesPerRead: Int = Int.MAX_VALUE,
) : PcmSource {
    private var at = 0
    var reads = 0

    override fun read(buffer: FloatArray): Int {
        reads++
        val left = (samples.size - at) / channelCount
        if (left <= 0) return -1
        val frames = minOf(left, buffer.size / channelCount, framesPerRead)
        samples.copyInto(buffer, 0, at, at + frames * channelCount)
        at += frames * channelCount
        return frames
    }
}

/** Serves one interleaved signal for every uri it knows. */
class FakePcmDecoder(
    private val samples: FloatArray,
    private val sampleRateHz: Int,
    private val channelCount: Int,
) : PcmDecoder {
    val streams = ArrayList<FakeStream>()

    override suspend fun open(sourceUri: String): PcmStream {
        if (sourceUri.startsWith("missing")) throw PcmDecodeException.SourceUnavailable(sourceUri)
        val format = PcmFormat(sampleRateHz, channelCount, null, "test", durationUs = null,
            totalFrames = (samples.size / channelCount).toLong())
        return FakeStream(format, samples).also { streams += it }
    }

    class FakeStream(override val format: PcmFormat, private val samples: FloatArray) : PcmStream {
        private var at = 0
        var closed = false

        override suspend fun read(buffer: FloatArray): Int {
            check(!closed)
            val channels = format.channelCount
            val left = (samples.size - at) / channels
            if (left <= 0) return -1
            val frames = minOf(left, buffer.size / channels)
            samples.copyInto(buffer, 0, at, at + frames * channels)
            at += frames * channels
            return frames
        }

        override fun close() {
            closed = true
        }
    }
}

/** A qm-dsp stand-in that answers [result] whatever it is fed. */
class FakeEngines(private val result: EngineResult = EngineResult(120.0, 0.0, 1, 0, 0, 10)) : AnalysisEngineFactory {
    var created = 0
    var closed = 0

    override fun create(sampleRateHz: Int): AnalysisEngine {
        created++
        return object : AnalysisEngine {
            override fun process(samples: FloatArray, count: Int) = Unit

            override fun finish(): EngineResult = result

            override fun close() {
                closed++
            }
        }
    }
}

/** An in-memory [DjAnalysisStore] with the default waveform-missing methods of the contract. */
class FakeStore : DjAnalysisStore {
    val rows = LinkedHashMap<AudioId, DjAnalysis>()
    var putCalls = 0
    var replaceCalls = 0

    override suspend fun get(id: AudioId): DjAnalysis? = rows[id]

    override fun observe(id: AudioId): Flow<DjAnalysis?> = flowOf(rows[id])

    override suspend fun analysedAmong(ids: Collection<AudioId>): Set<AudioId> = ids.filterTo(HashSet()) { it in rows }

    override suspend fun putIfAbsent(id: AudioId, analysis: DjAnalysis): Boolean {
        putCalls++
        if (id in rows) return false
        rows[id] = analysis
        return true
    }

    override suspend fun replace(id: AudioId, analysis: DjAnalysis) {
        replaceCalls++
        rows[id] = analysis
    }
}

class RecordingLog : DjAnalysisLog {
    val infos = ArrayList<String>()
    val warnings = ArrayList<Pair<String, Throwable?>>()

    override fun info(message: String) {
        infos += message
    }

    override fun warn(message: String, cause: Throwable?) {
        warnings += message to cause
    }
}

/** A monotonic clock that moves 1 ms each time it is read. */
class SteppingClock {
    private var nanos = 0L

    fun read(): Long {
        nanos += 1_000_000L
        return nanos
    }
}

/** An in-memory [DjGridEditStore]: edits are observed, and every write is recorded in [writes] (`null` for a delete). */
class FakeGridEditStore : DjGridEditStore {
    private val flows = HashMap<AudioId, MutableStateFlow<GridEdit?>>()
    val observing = HashSet<AudioId>()
    val writes = ArrayList<Pair<AudioId, GridEdit?>>()

    private fun flowOf(id: AudioId) = flows.getOrPut(id) { MutableStateFlow(null) }

    fun set(id: AudioId, edit: GridEdit?) {
        flowOf(id).value = edit
    }

    override suspend fun get(id: AudioId): GridEdit? = flowOf(id).value

    override fun observe(id: AudioId): Flow<GridEdit?> = flowOf(id).onStart { observing += id }.onCompletion { observing -= id }

    override suspend fun put(id: AudioId, edit: GridEdit) {
        writes += id to edit
        flowOf(id).value = edit
    }

    override suspend fun delete(id: AudioId) {
        writes += id to null
        flowOf(id).value = null
    }
}

/** An in-memory [DjCueStore]: cues are observed (sorted), and every write is recorded in [writes] (`null` cue for a delete). */
class FakeCueStore : DjCueStore {
    private val flows = HashMap<AudioId, MutableStateFlow<List<DjCue>>>()
    val observing = HashSet<AudioId>()
    val writes = ArrayList<Triple<AudioId, String, DjCue?>>()

    private fun flowOf(id: AudioId) = flows.getOrPut(id) { MutableStateFlow(emptyList()) }

    override suspend fun get(id: AudioId): List<DjCue> = flowOf(id).value

    override fun observe(id: AudioId): Flow<List<DjCue>> = flowOf(id).onStart { observing += id }.onCompletion { observing -= id }

    override suspend fun put(id: AudioId, cue: DjCue) {
        writes += Triple(id, cue.id, cue)
        flowOf(id).value = DjCues.sorted(flowOf(id).value.filterNot { it.id == cue.id } + cue)
    }

    override suspend fun delete(id: AudioId, cueId: String) {
        writes += Triple(id, cueId, null)
        flowOf(id).value = flowOf(id).value.filterNot { it.id == cueId }
    }
}
