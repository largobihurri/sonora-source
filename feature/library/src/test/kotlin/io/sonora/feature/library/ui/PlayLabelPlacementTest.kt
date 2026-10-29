package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayLabelPlacementTest {

    @Test
    fun `the label sits in the middle of the capsule when it clears the icon`() {
        // A 500 px capsule centres a 160 px label at 170, clear of the icon, which ends at 130.
        assertThat(playLabelStart(capsuleWidth = 500, labelWidth = 160, minStart = 130)).isEqualTo(170)
    }

    @Test
    fun `the label never runs into the icon`() {
        // Centred, a 160 px label would start at 120 in a 400 px capsule, inside the icon; it starts after it.
        assertThat(playLabelStart(capsuleWidth = 400, labelWidth = 160, minStart = 130)).isEqualTo(130)
    }

    @Test
    fun `the capsule keeps the round buttons' height and grows only for a taller label`() {
        assertThat(playCapsuleHeight(labelHeight = 60, minHeight = 150, padding = 20)).isEqualTo(150)
        // A large font: a 140 px label with 20 px above and below.
        assertThat(playCapsuleHeight(labelHeight = 140, minHeight = 150, padding = 20)).isEqualTo(180)
    }
}
