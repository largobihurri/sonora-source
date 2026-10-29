package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StickPickerStartTest {

    @Test
    fun `the picker opens at the root of the only mounted removable volume`() {
        val volumes = listOf(
            VolumeSummary(uuid = null, removable = false, mounted = true),
            VolumeSummary(uuid = "1111-2222", removable = true, mounted = false),
            VolumeSummary(uuid = "2C9C-2151", removable = true, mounted = true),
        )

        assertThat(removableRootDocumentId(volumes)).isEqualTo("2C9C-2151:")
    }

    @Test
    fun `with several mounted removable volumes the picker does not guess`() {
        // An SD card and a stick look alike to apps; opening the wrong one would invite picking it.
        val volumes = listOf(
            VolumeSummary(uuid = "2C9C-2151", removable = true, mounted = true),
            VolumeSummary(uuid = "3333-4444", removable = true, mounted = true),
        )

        assertThat(removableRootDocumentId(volumes)).isNull()
    }

    @Test
    fun `without a mounted removable volume the picker opens where it likes`() {
        val volumes = listOf(
            VolumeSummary(uuid = null, removable = false, mounted = true),
            VolumeSummary(uuid = "1111-2222", removable = true, mounted = false),
            VolumeSummary(uuid = null, removable = true, mounted = true),
        )

        assertThat(removableRootDocumentId(volumes)).isNull()
    }
}
