package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class HistoryGroupingTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun millis(dateTime: String, zone: ZoneId = madrid) =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun clockAt(dateTime: String, zone: ZoneId = madrid) =
        Clock.fixed(LocalDateTime.parse(dateTime).atZone(zone).toInstant(), zone)

    private fun batch(id: Long, createdAt: String, zone: ZoneId = madrid) = ConversionBatch(
        id = id,
        profileId = "xdj-700",
        performanceMode = PerformanceMode.NORMAL,
        createdAtMs = millis(createdAt, zone),
        outputFolderName = "x",
        outputFolderUri = null,
        overwriteOriginals = false,
        status = BatchStatus.COMPLETED,
    )

    private fun List<HistoryGroup>.ids() = associate { group -> group.section to group.batches.map { it.id } }

    @Test
    fun `batches fall into calendar-day sections, newest first`() {
        val clock = clockAt("2026-10-03T00:30:00")
        val batches = listOf(
            batch(1, "2026-10-03T00:10:00"), // today, just after midnight
            batch(2, "2026-10-02T23:59:00"), // yesterday, a minute before midnight
            batch(3, "2026-10-02T00:00:00"),
            batch(4, "2026-10-01T12:00:00"), // 2 days ago
            batch(5, "2026-09-27T00:00:00"), // 6 days ago: still the last 7 days
            batch(6, "2026-09-26T23:00:00"), // 7 days ago
            batch(7, "2026-09-04T00:00:00"), // 29 days ago
            batch(8, "2026-09-03T23:00:00"), // 30 days ago
            batch(9, "2025-01-01T00:00:00"),
        )

        assertThat(HistoryGrouping.group(batches.shuffled(), clock).ids()).containsExactly(
            HistorySection.TODAY, listOf(1L),
            HistorySection.YESTERDAY, listOf(2L, 3L),
            HistorySection.LAST_7_DAYS, listOf(4L, 5L),
            HistorySection.LAST_30_DAYS, listOf(6L, 7L),
            HistorySection.OLDER, listOf(8L, 9L),
        ).inOrder()
    }

    @Test
    fun `empty sections are left out`() {
        val groups = HistoryGrouping.group(listOf(batch(1, "2026-09-01T10:00:00")), clockAt("2026-10-03T12:00:00"))
        assertThat(groups.ids()).containsExactly(HistorySection.OLDER, listOf(1L))
        assertThat(HistoryGrouping.group(emptyList(), clockAt("2026-10-03T12:00:00"))).isEmpty()
    }

    @Test
    fun `days follow the clock's time zone`() {
        // 23:30 UTC on 2 October is already 3 October in Madrid.
        val batches = listOf(batch(1, "2026-10-02T23:30:00", ZoneOffset.UTC))
        assertThat(HistoryGrouping.group(batches, clockAt("2026-10-03T10:00:00", madrid)).ids())
            .containsExactly(HistorySection.TODAY, listOf(1L))
        assertThat(HistoryGrouping.group(batches, clockAt("2026-10-03T10:00:00", ZoneOffset.UTC)).ids())
            .containsExactly(HistorySection.YESTERDAY, listOf(1L))
    }

    @Test
    fun `a batch from the future (clock moved back) counts as today`() {
        val groups = HistoryGrouping.group(listOf(batch(1, "2026-10-05T10:00:00")), clockAt("2026-10-03T10:00:00"))
        assertThat(groups.ids()).containsExactly(HistorySection.TODAY, listOf(1L))
    }

    @Test
    fun `batches created in the same millisecond keep the newest id first`() {
        val groups = HistoryGrouping.group(
            listOf(batch(1, "2026-10-03T09:00:00"), batch(2, "2026-10-03T09:00:00")),
            clockAt("2026-10-03T10:00:00"),
        )
        assertThat(groups.ids()).containsExactly(HistorySection.TODAY, listOf(2L, 1L))
    }
}
