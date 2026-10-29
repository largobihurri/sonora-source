package io.sonora.feature.converter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.HistoryGroup
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import org.junit.Test

class ConverterRulesTest {

    private fun batch(id: Long, profileId: String = ConversionProfiles.XDJ_700.id, status: BatchStatus = BatchStatus.COMPLETED) =
        ConversionBatch(id, profileId, PerformanceMode.NORMAL, id * 1_000, "folder $id", null, overwriteOriginals = false, status = status)

    private fun item(position: Int, parent: String? = "content://tree/dj") =
        ConversionBatchItem(position.toLong(), 1, position, "content://doc/$position", "$position.mp3", parent, profileId = "xdj-700")

    @Test
    fun `history sections keep their order and name each batch's profile`() {
        val groups = listOf(
            HistoryGroup(HistorySection.TODAY, listOf(batch(3), batch(2, status = BatchStatus.RUNNING))),
            HistoryGroup(HistorySection.LAST_30_DAYS, listOf(batch(1, profileId = "cdj-3000"))),
        )

        val sections = ConverterRules.historySections(groups)

        assertThat(sections.map { it.section }).containsExactly(HistorySection.TODAY, HistorySection.LAST_30_DAYS).inOrder()
        assertThat(sections[0].entries.map { it.batch.id }).containsExactly(3L, 2L).inOrder()
        assertThat(sections[0].entries[0].profileName).isEqualTo("Pioneer XDJ-700")
        // A profile this version does not know (history from a newer one) shows its id rather than nothing.
        assertThat(sections[1].entries.single().profileName).isEqualTo("cdj-3000")
    }

    @Test
    fun `a batch shows the one format its files share, even when it is not the batch-wide one`() {
        // Batch-wide XDJ-700, but its only song was set by hand to MP3 320.
        val byHand = batch(1).copy(profileIds = setOf("out:mp3:cbr:320"))
        val mixed = batch(2).copy(profileIds = setOf("xdj-700", "out:mp3:cbr:320"))
        val notStored = batch(3, profileId = "out:flac:24:keep")

        assertThat(ConverterRules.batchProfileId(byHand)).isEqualTo("out:mp3:cbr:320")
        assertThat(ConverterRules.batchProfileId(mixed)).isNull()
        // Without its files on record, the batch-wide one.
        assertThat(ConverterRules.batchProfileId(notStored)).isEqualTo("out:flac:24:keep")
    }

    @Test
    fun `an empty history has no sections`() {
        assertThat(ConverterRules.historySections(emptyList())).isEmpty()
    }

    @Test
    fun `only verified outputs from picked folders are replaceable`() {
        val items = listOf(
            item(0).converted(),
            item(1, parent = null).converted(),
            item(2).copy(state = BatchItemState.FAILED, failureReason = "corrupt"),
            item(3).converted().copy(state = BatchItemState.FAILED, failureReason = "replace failed"),
            item(4).converted().copy(state = BatchItemState.REPLACED_ORIGINAL),
        )

        assertThat(ConverterRules.replaceableCount(items)).isEqualTo(2)
    }

    @Test
    fun `replaceable outputs that would turn a lossless original into a lossy file are counted`() {
        fun converted(name: String, profileId: String, parent: String? = "content://tree/dj") =
            item(0, parent).copy(sourceName = name, profileId = profileId).converted()
        val items = listOf(
            converted("a.flac", "out:mp3:cbr:320"),
            converted("b.WAV", "out:aac:256"),
            converted("c.flac", "out:flac:24:keep"), // lossless output
            converted("d.mp3", "out:opus:160"), // lossy original
            converted("e.aiff", "out:mp3:cbr:320", parent = null), // cannot be replaced
            converted("f.flac", "a-newer-profile"), // unknown profile: not known to be lossy
        )

        assertThat(ConverterRules.lossyReplaceableCount(items)).isEqualTo(2)
    }

    @Test
    fun `a finished batch asks to replace only when it ended on its own and has something to replace`() {
        val replaceable = listOf(item(0).converted())

        fun asks(status: BatchStatus, overwrite: Boolean = false, items: List<ConversionBatchItem> = replaceable) =
            ConverterRules.asksToReplace(BatchSnapshot(batch(1, status = status).copy(overwriteOriginals = overwrite), items, null))

        assertThat(asks(BatchStatus.COMPLETED)).isTrue()
        assertThat(asks(BatchStatus.TIMED_OUT)).isTrue()
        assertThat(asks(BatchStatus.CANCELLED)).isFalse()
        assertThat(asks(BatchStatus.RUNNING)).isFalse()
        assertThat(asks(BatchStatus.COMPLETED, overwrite = true)).isFalse()
        assertThat(asks(BatchStatus.COMPLETED, items = listOf(item(0)))).isFalse()
    }

    @Test
    fun `only converted files with an output can be played`() {
        val output = "content://out/x.aiff"
        assertThat(ConverterRules.isPlayable(item(0).copy(state = BatchItemState.DONE, outputUri = output))).isTrue()
        assertThat(ConverterRules.isPlayable(item(1).copy(state = BatchItemState.REPLACED_ORIGINAL, outputUri = output))).isTrue()
        // A failed replacement keeps its verified output in the batch folder.
        assertThat(ConverterRules.isPlayable(item(2).copy(state = BatchItemState.FAILED, outputUri = output, result = RESULT))).isTrue()
        assertThat(ConverterRules.isPlayable(item(3).copy(state = BatchItemState.FAILED, failureReason = "damaged"))).isFalse()
        assertThat(ConverterRules.isPlayable(item(4).copy(state = BatchItemState.SKIPPED))).isFalse()
        assertThat(ConverterRules.isPlayable(item(5))).isFalse()
        assertThat(ConverterRules.isPlayable(item(6).copy(state = BatchItemState.REPLACED_ORIGINAL))).isFalse()
    }
}
