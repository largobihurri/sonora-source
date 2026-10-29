package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.cdjexport.export.ExportResult
import org.junit.Test

/** Where "Eject stick" shows: under a finished export or format, never while one is still writing. */
class EjectPlaceTest {

    private val device = DeviceItem("id", "Stick", "0781:5581", isSupported = true)
    private val details = StickDetails("SanDisk", "SanDisk", 16_008_609_792, 512, false)
    private val formatted = PrepareStep.Finished(device, FormatOutcome.Formatted("SONORA", 16_000_000_000))
    private val formatting = PrepareStep.Formatting(device, details, "SONORA")
    private val exported = ExportStep.Done(ExportResult.Completed(emptyList(), emptyList(), emptyList(), null, 3, libraryWritten = true), null)
    private val exporting = ExportStep.Running("SONORA", 3, progress = null, cancelling = false)

    @Test
    fun `a finished export or format offers the eject, the export's first`() {
        assertThat(ejectPlace(formatted, ExportStep.Setup)).isEqualTo(EjectPlace.FORMAT)
        assertThat(ejectPlace(PrepareStep.ChooseDevice, exported)).isEqualTo(EjectPlace.EXPORT)
        assertThat(ejectPlace(formatted, exported)).isEqualTo(EjectPlace.EXPORT)
    }

    @Test
    fun `nothing to eject without a finished export or a stick formatted`() {
        assertThat(ejectPlace(PrepareStep.ChooseDevice, ExportStep.Setup)).isEqualTo(EjectPlace.NONE)
        assertThat(ejectPlace(PrepareStep.Finished(device, FormatOutcome.Failed(null)), ExportStep.Setup))
            .isEqualTo(EjectPlace.NONE)
    }

    @Test
    fun `no eject while an export or a format is writing to a stick`() {
        assertThat(ejectPlace(formatted, exporting)).isEqualTo(EjectPlace.NONE)
        assertThat(ejectPlace(formatting, exported)).isEqualTo(EjectPlace.NONE)
        assertThat(ejectPlace(formatting.copy(cancelling = true), exported)).isEqualTo(EjectPlace.NONE)
        assertThat(ejectPlace(formatted, exporting.copy(cancelling = true))).isEqualTo(EjectPlace.NONE)
    }
}
