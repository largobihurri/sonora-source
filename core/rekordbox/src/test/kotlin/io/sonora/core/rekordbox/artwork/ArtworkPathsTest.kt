package io.sonora.core.rekordbox.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtworkPathsTest {

    @Test
    fun `the first picture is a1 in folder 00001, its high-resolution copy a1_m`() {
        assertThat(ArtworkPaths.small(1)).isEqualTo("/PIONEER/Artwork/00001/a1.jpg")
        assertThat(ArtworkPaths.large(1)).isEqualTo("/PIONEER/Artwork/00001/a1_m.jpg")
    }

    @Test
    fun `folders hold twenty pictures each, as in rekordbox's num_rows export`() {
        // rekordcrate data/pdb/num_rows/export.pdb: 2178 artwork rows, each id N at /PIONEER/Artwork/<N/20+1>/aN.jpg,
        // e.g. a19 in 00001, a20 and a39 in 00002, a40 in 00003, a100..a119 in 00006, a120 in 00007.
        val expected = mapOf(19 to "00001", 20 to "00002", 39 to "00002", 40 to "00003", 100 to "00006", 119 to "00006", 120 to "00007")

        for ((id, folder) in expected) {
            assertThat(ArtworkPaths.small(id)).isEqualTo("/PIONEER/Artwork/$folder/a$id.jpg")
            assertThat(ArtworkPaths.large(id)).isEqualTo("/PIONEER/Artwork/$folder/a${id}_m.jpg")
        }
    }

    @Test
    fun `the players' picture sizes are 80 and 240 pixels square`() {
        assertThat(ArtworkPaths.SMALL_PIXELS).isEqualTo(80)
        assertThat(ArtworkPaths.LARGE_PIXELS).isEqualTo(240)
    }

    @Test
    fun `artwork ids start at 1`() {
        assertThrows(IllegalArgumentException::class.java) { ArtworkPaths.small(0) }
    }
}
