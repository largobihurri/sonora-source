package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.model.ConversionBatch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Sections of the converter history, newest first. */
enum class HistorySection { TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, OLDER }

/** One non-empty history section and its batches, newest first. */
data class HistoryGroup(val section: HistorySection, val batches: List<ConversionBatch>)

/** Groups batches by calendar day in the [Clock]'s time zone, relative to the clock's current day. */
object HistoryGrouping {

    fun group(batches: List<ConversionBatch>, clock: Clock): List<HistoryGroup> {
        val today = LocalDate.now(clock)
        return batches
            .sortedWith(compareByDescending<ConversionBatch> { it.createdAtMs }.thenByDescending { it.id })
            .groupBy { section(it.createdAtMs, today, clock) }
            .toSortedMap()
            .map { (section, members) -> HistoryGroup(section, members) }
    }

    private fun section(createdAtMs: Long, today: LocalDate, clock: Clock): HistorySection {
        val day = Instant.ofEpochMilli(createdAtMs).atZone(clock.zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today)
        return when {
            daysAgo <= 0 -> HistorySection.TODAY
            daysAgo == 1L -> HistorySection.YESTERDAY
            daysAgo < 7 -> HistorySection.LAST_7_DAYS
            daysAgo < 30 -> HistorySection.LAST_30_DAYS
            else -> HistorySection.OLDER
        }
    }
}
