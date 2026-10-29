package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlossyGrabberTest {

    @Test
    fun `the body reads as bright as the plain handle it replaces, lit from above`() {
        val gloss = GrabberGloss.Default

        assertThat((gloss.bodyTop + gloss.bodyBottom) / 2f).isWithin(0.05f).of(GrabberGloss.PLAIN_ALPHA)
        assertThat(gloss.bodyTop).isGreaterThan(gloss.bodyBottom)
    }

    @Test
    fun `a specular sheen sits on the upper half, brighter than the body, fading along the capsule`() {
        val gloss = GrabberGloss.Default

        assertThat(gloss.sheenPeak).isGreaterThan(gloss.bodyTop)
        assertThat(gloss.sheenBottom).isAtMost(0.5f)
        assertThat(gloss.sheenTop).isLessThan(gloss.sheenBottom)
        assertThat(gloss.sheenEnd).isLessThan(1f)
        assertThat(gloss.sheenEnd).isGreaterThan(gloss.sheenStart)
    }

    @Test
    fun `the rim catches the light on top and fades below, as the translucent rim does`() {
        val gloss = GrabberGloss.Default

        assertThat(gloss.rimTop).isGreaterThan(gloss.rimBottom)
        assertThat(gloss.rimTop).isAtMost(1f)
        assertThat(gloss.rimBottom).isAtLeast(0f)
    }

    @Test
    fun `the lit rim is as bright as the translucent surfaces' specular rim`() {
        assertThat(GrabberGloss.Default.rimTop).isAtLeast(0.85f)
    }

    @Test
    fun `a faint caustic glows low on the far end, fainter than the sheen`() {
        val gloss = GrabberGloss.Default

        assertThat(gloss.causticPeak).isGreaterThan(0f)
        assertThat(gloss.causticPeak).isLessThan(gloss.sheenPeak)
        assertThat(gloss.causticX).isGreaterThan(0.5f)
        assertThat(gloss.causticX).isAtMost(1f)
        assertThat(gloss.causticY).isGreaterThan(0.5f)
        assertThat(gloss.causticY).isAtMost(1f)
    }
}
