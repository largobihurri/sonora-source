package io.sonora.core.domain.analysis

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class CachedTrackAnalyzerTest {

    private val analyzer = CountingAnalyzer()
    private val cache = MapAnalysisCache()
    private val failures = mutableListOf<Throwable>()
    private val cached = CachedTrackAnalyzer(analyzer, cache, VERSION, onCacheFailure = { failures += it })

    @Test
    fun `a miss analyses the track and stores the result under its content and version`() = runBlocking<Unit> {
        val result = cached.analyze("content://one", SHA_ONE)

        assertThat(result).isEqualTo(analysis(VERSION))
        assertThat(analyzer.analysed).containsExactly("content://one")
        assertThat(cache.entries).containsExactly(SHA_ONE to VERSION, analysis(VERSION))
    }

    @Test
    fun `the same content is not analysed again, whatever its file is called`() = runBlocking<Unit> {
        cached.analyze("content://one", SHA_ONE)
        val progress = mutableListOf<Float>()

        val again = cached.analyze("content://renamed", SHA_ONE) { progress += it }

        assertThat(again).isEqualTo(analysis(VERSION))
        assertThat(analyzer.analysed).containsExactly("content://one")
        assertThat(progress).containsExactly(1f)
    }

    @Test
    fun `other content is analysed`() = runBlocking<Unit> {
        cached.analyze("content://one", SHA_ONE)

        cached.analyze("content://two", SHA_TWO)

        assertThat(analyzer.analysed).containsExactly("content://one", "content://two").inOrder()
    }

    @Test
    fun `a new analyser version misses and prunes the results of older versions`() = runBlocking<Unit> {
        cache.entries[SHA_ONE to "old/1"] = analysis("old/1")
        cache.entries[SHA_TWO to "old/1"] = analysis("old/1")

        val result = cached.analyze("content://one", SHA_ONE)

        assertThat(result.analyzerVersion).isEqualTo(VERSION)
        assertThat(analyzer.analysed).containsExactly("content://one")
        assertThat(cache.entries.keys).containsExactly(SHA_ONE to VERSION)
    }

    @Test
    fun `older versions are pruned once, not on every track`() = runBlocking<Unit> {
        cached.analyze("content://one", SHA_ONE)
        cached.analyze("content://two", SHA_TWO)

        assertThat(cache.prunes).containsExactly(VERSION)
    }

    @Test
    fun `an unreadable entry is a miss, is removed and is replaced by a fresh analysis`() = runBlocking<Unit> {
        cache.entries[SHA_ONE to VERSION] = analysis(VERSION)
        cache.unreadable += SHA_ONE

        val result = cached.analyze("content://one", SHA_ONE)

        assertThat(result).isEqualTo(analysis(VERSION))
        assertThat(analyzer.analysed).containsExactly("content://one")
        assertThat(cache.removed).containsExactly(SHA_ONE to VERSION)
        assertThat(cache.entries).containsExactly(SHA_ONE to VERSION, analysis(VERSION))
        assertThat(failures).hasSize(1)
    }

    @Test
    fun `a cache that fails everywhere never fails the analysis`() = runBlocking<Unit> {
        cache.broken = true

        val result = cached.analyze("content://one", SHA_ONE)

        assertThat(result).isEqualTo(analysis(VERSION))
        assertThat(analyzer.analysed).containsExactly("content://one")
        assertThat(failures).isNotEmpty()
    }

    @Test
    fun `a failed analysis propagates and stores nothing`() {
        analyzer.failure = AnalysisException.NoBeatsDetected()

        assertThrows(AnalysisException.NoBeatsDetected::class.java) {
            runBlocking { cached.analyze("content://one", SHA_ONE) }
        }
        assertThat(cache.entries).isEmpty()
    }

    @Test
    fun `a result stamped with another version than expected is returned but not stored`() = runBlocking<Unit> {
        analyzer.version = "surprise/9"

        val result = cached.analyze("content://one", SHA_ONE)

        assertThat(result.analyzerVersion).isEqualTo("surprise/9")
        assertThat(cache.entries).isEmpty()
        assertThat(failures).hasSize(1)
    }

    @Test
    fun `forgetting content removes its result, so the next analysis decodes it again`() = runBlocking<Unit> {
        cached.analyze("content://one", SHA_ONE)
        cached.analyze("content://two", SHA_TWO)

        cached.forget(SHA_ONE)
        cached.analyze("content://one", SHA_ONE)

        assertThat(cache.removed).containsExactly(SHA_ONE to VERSION)
        assertThat(analyzer.analysed).containsExactly("content://one", "content://two", "content://one").inOrder()
        assertThat(cache.entries.keys).containsExactly(SHA_TWO to VERSION, SHA_ONE to VERSION)
    }

    @Test
    fun `forgetting through a broken cache is reported, not thrown`() = runBlocking<Unit> {
        cache.broken = true

        cached.forget(SHA_ONE)

        assertThat(failures).hasSize(1)
    }

    @Test
    fun `content hashes must be lower-case SHA-256 hex`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cached.analyze("content://one", "not-a-hash") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cached.analyze("content://one", SHA_TWO.uppercase()) }
        }
        assertThat(analyzer.analysed).isEmpty()
    }

    private class CountingAnalyzer : TrackAnalyzer {
        val analysed = mutableListOf<String>()
        var failure: Exception? = null
        var version = VERSION

        override suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit): TrackAnalysis {
            failure?.let { throw it }
            analysed += sourceUri
            onProgress(1f)
            return analysis(version)
        }
    }

    /** [unreadable] content fails to read back once; [broken] makes every call fail. */
    private class MapAnalysisCache : AnalysisCache {
        val entries = LinkedHashMap<Pair<String, String>, TrackAnalysis>()
        val unreadable = mutableSetOf<String>()
        val removed = mutableListOf<Pair<String, String>>()
        val prunes = mutableListOf<String>()
        var broken = false

        override suspend fun get(contentSha256: String, analyzerVersion: String): TrackAnalysis? {
            check(!broken) { "cache broken" }
            if (unreadable.remove(contentSha256)) throw IllegalArgumentException("corrupt row")
            return entries[contentSha256 to analyzerVersion]
        }

        override suspend fun put(contentSha256: String, analysis: TrackAnalysis) {
            check(!broken) { "cache broken" }
            entries[contentSha256 to analysis.analyzerVersion] = analysis
        }

        override suspend fun remove(contentSha256: String, analyzerVersion: String) {
            check(!broken) { "cache broken" }
            removed += contentSha256 to analyzerVersion
            entries.remove(contentSha256 to analyzerVersion)
        }

        override suspend fun prune(keepAnalyzerVersion: String) {
            check(!broken) { "cache broken" }
            prunes += keepAnalyzerVersion
            entries.keys.removeAll { it.second != keepAnalyzerVersion }
        }
    }

    private companion object {
        const val VERSION = "test-analysis/2"
        val SHA_ONE = "1".repeat(64)
        val SHA_TWO = "ab".repeat(32)

        fun analysis(version: String) = TrackAnalysis(
            beatGrid = BeatGrid(bpm = 124.0, firstBeatMs = 12.5, firstBeatInBar = 2, beatCount = 400),
            key = MusicalKey.F_MINOR,
            waveform = BlueWaveform(
                detail = ByteArray(3000) { BlueWaveform.packColumn(it % 32, it % 8) },
                preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { BlueWaveform.packColumn(it % 32, 1) },
                tinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
            ),
            durationMs = 20_000,
            analyzerVersion = version,
        )
    }
}
