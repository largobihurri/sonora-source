package io.sonora.core.domain.model

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import org.junit.Test

class ConversionBatchModelTest {

    private val result = ConversionResult(ConversionPlan(ConversionProfiles.XDJ_700, 44_100, 44_100, 2, 2, 16, false), 1, 1)

    private val item = ConversionBatchItem(1, 1, 0, "content://a.flac", "a.flac", "content://music", profileId = ConversionProfiles.XDJ_700.id)

    @Test
    fun `a file whose original could not be replaced still counts as converted, and as failed`() {
        val counts = BatchCounts(total = 3, done = 1, failed = 2, replacedOriginals = 0, notReplaced = 1)

        assertThat(counts.converted).isEqualTo(2)
        assertThat(counts.failed).isEqualTo(2)
        assertThat(counts.finished).isEqualTo(3)
    }

    @Test
    fun `only a verified output still in the batch folder can replace its original`() {
        val output = item.copy(outputUri = "content://out/a.aiff", result = result)

        assertThat(output.copy(state = BatchItemState.DONE).canReplaceOriginal).isTrue()
        // A failed replacement keeps its output in the batch folder: it can be tried again.
        assertThat(output.copy(state = BatchItemState.FAILED).canReplaceOriginal).isTrue()
        // A failed conversion has no output; a replaced one already moved; a running one is not done yet.
        assertThat(item.copy(state = BatchItemState.FAILED).canReplaceOriginal).isFalse()
        assertThat(output.copy(state = BatchItemState.REPLACED_ORIGINAL).canReplaceOriginal).isFalse()
        assertThat(output.copy(state = BatchItemState.RUNNING).canReplaceOriginal).isFalse()
        assertThat(output.copy(state = BatchItemState.SKIPPED).canReplaceOriginal).isFalse()
        // Without the conversion's result the copy next to the original could not be verified.
        assertThat(output.copy(state = BatchItemState.DONE, result = null).canReplaceOriginal).isFalse()
    }

    @Test
    fun `a converted file is done, replaced, or kept after a failed replacement`() {
        val output = item.copy(outputUri = "content://out/a.aiff", result = result)

        assertThat(output.copy(state = BatchItemState.DONE).isConverted).isTrue()
        assertThat(output.copy(state = BatchItemState.REPLACED_ORIGINAL).isConverted).isTrue()
        assertThat(output.copy(state = BatchItemState.FAILED).isConverted).isTrue()
        assertThat(item.copy(state = BatchItemState.FAILED).isConverted).isFalse()
        assertThat(output.copy(state = BatchItemState.FAILED, result = null).isConverted).isFalse()
        assertThat(item.copy(state = BatchItemState.SKIPPED).isConverted).isFalse()
    }

    @Test
    fun `a batch whose files convert to several profiles says so`() {
        val batch = ConversionBatch(1, "xdj-700", io.sonora.core.domain.conversion.PerformanceMode.NORMAL, 0, "f", null, false, BatchStatus.COMPLETED)

        assertThat(batch.copy(profileIds = setOf("xdj-700", "watch-opus")).hasMixedProfiles).isTrue()
        assertThat(batch.copy(profileIds = setOf("xdj-700")).hasMixedProfiles).isFalse()
        // Not read from the history yet: nothing says the files differ.
        assertThat(batch.hasMixedProfiles).isFalse()
    }

    @Test
    fun `a file converts to the default profile unless it names its own`() {
        assertThat(item.profileId).isEqualTo(ConversionProfiles.XDJ_700.id)
        assertThat(item.copy(profileId = "watch-opus").profileId).isEqualTo("watch-opus")
    }
}
