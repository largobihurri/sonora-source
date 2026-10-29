package io.sonora.core.domain.library

import io.sonora.core.domain.conversion.batch.HistoryGrouping
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConversionBatchRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** One batch's converted songs that still exist, in batch order; [createdAtMs] is when the batch was made. */
data class ConvertedBatchTracks(val batchId: Long, val createdAtMs: Long, val tracks: List<Track>)

/** One date section of Library > Converted: its batches' converted songs, newest batch first. */
data class ConvertedMusicSection(val section: HistorySection, val batches: List<ConvertedBatchTracks>) {
    /** Every song of the section in display order. */
    val tracks: List<Track> = batches.flatMap { it.tracks }
}

/**
 * Library > Converted: every song the converter produced that still exists, wherever it is now, in the converter
 * history's date sections ([HistoryGrouping]): newest batch first, each batch's songs in batch order. Outputs that
 * are gone are left out silently, and so are sections left empty. Each file is opened through
 * [ResolveConvertedTracks]; the files are read again only when a batch's outcome changes (a batch appears or goes,
 * ends, converts or replaces files), not on every progress update. The sections are regrouped, without reading the
 * files, whenever [dayChecks] emits and the [clock]'s day has changed (so "Today" becomes "Yesterday" at midnight).
 */
class ObserveConvertedMusic(
    private val repository: ConversionBatchRepository,
    private val resolve: ResolveConvertedTracks,
    private val clock: Clock,
    /** When to look at the day again; by default at each midnight and at least every minute ([dayCheckTimer]). */
    private val dayChecks: Flow<Unit> = dayCheckTimer(clock),
) {
    @OptIn(ExperimentalCoroutinesApi::class) // mapLatest
    operator fun invoke(): Flow<List<ConvertedMusicSection>> = repository.batches()
        .distinctUntilChangedBy { batches -> batches.map(::outcome) }
        .mapLatest(::load)
        .combine(days()) { loaded, _ -> group(loaded) }
        .distinctUntilChanged()

    /** The clock's day now and after each [dayChecks] emission, only when it changed. */
    private fun days(): Flow<LocalDate> = dayChecks.onStart { emit(Unit) }.map { LocalDate.now(clock) }.distinctUntilChanged()

    /** The batches and the songs of each one that still has some. */
    private class Loaded(val batches: List<ConversionBatch>, val tracks: Map<Long, ConvertedBatchTracks>)

    private suspend fun load(batches: List<ConversionBatch>): Loaded {
        val tracks = batches.mapNotNull { batch ->
            resolve(batch.id).tracks.takeIf { it.isNotEmpty() }?.let { batch.id to ConvertedBatchTracks(batch.id, batch.createdAtMs, it) }
        }
        return Loaded(batches, tracks.toMap())
    }

    private fun group(loaded: Loaded): List<ConvertedMusicSection> =
        HistoryGrouping.group(loaded.batches.filter { it.id in loaded.tracks }, clock).map { group ->
            ConvertedMusicSection(group.section, group.batches.map { loaded.tracks.getValue(it.id) })
        }

    private data class Outcome(val id: Long, val status: BatchStatus, val converted: Int, val replaced: Int, val failed: Int)

    private fun outcome(batch: ConversionBatch) =
        Outcome(batch.id, batch.status, batch.counts.converted, batch.counts.replacedOriginals, batch.counts.failed)

    companion object {
        /** The longest wait between two looks at the day: a delay does not run while the device sleeps. */
        private const val MAX_DAY_CHECK_MS = 60_000L

        /** Emits at the [clock]'s next midnight, and at least every minute. */
        fun dayCheckTimer(clock: Clock): Flow<Unit> = flow {
            while (true) {
                delay(untilNextDayCheckMs(clock))
                emit(Unit)
            }
        }

        /** Milliseconds until the [clock]'s next midnight in its zone, at least 1 and at most a minute. */
        internal fun untilNextDayCheckMs(clock: Clock): Long {
            val now = ZonedDateTime.now(clock)
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(clock.zone)
            return Duration.between(now, midnight).toMillis().coerceIn(1, MAX_DAY_CHECK_MS)
        }
    }
}
