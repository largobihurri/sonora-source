package io.sonora.core.designsystem.translucent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentPolicyTest {

    private fun policy(
        sdkInt: Int = 36,
        enabled: Boolean = true,
        reducedMotion: Boolean = false,
        highContrastText: Boolean = false,
        ceiling: TranslucentTier = TranslucentTier.LIQUID,
    ) = TranslucentPolicy.resolve(sdkInt, enabled, reducedMotion, highContrastText, ceiling)

    @Test
    fun `refraction needs Android 13, blur alone Android 12, older versions stay flat`() {
        assertThat(TranslucentPolicy.tierFor(36)).isEqualTo(TranslucentTier.LIQUID)
        assertThat(TranslucentPolicy.tierFor(33)).isEqualTo(TranslucentTier.LIQUID)
        assertThat(TranslucentPolicy.tierFor(32)).isEqualTo(TranslucentTier.FROSTED)
        assertThat(TranslucentPolicy.tierFor(31)).isEqualTo(TranslucentTier.FROSTED)
        assertThat(TranslucentPolicy.tierFor(30)).isEqualTo(TranslucentTier.FLAT)
        assertThat(TranslucentPolicy.tierFor(29)).isEqualTo(TranslucentTier.FLAT)
    }

    @Test
    fun `with the switch on each Android version draws the most translucency it can`() {
        assertThat(policy(sdkInt = 35).tier).isEqualTo(TranslucentTier.LIQUID)
        assertThat(policy(sdkInt = 32).tier).isEqualTo(TranslucentTier.FROSTED)
        assertThat(policy(sdkInt = 29).tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(policy(sdkInt = 35).samplesBackdrop).isTrue()
        assertThat(policy(sdkInt = 31).samplesBackdrop).isTrue()
        assertThat(policy(sdkInt = 30).samplesBackdrop).isFalse()
    }

    @Test
    fun `the switch off falls back to the flat translucent surface everywhere`() {
        for (sdk in 29..37) {
            val off = policy(sdkInt = sdk, enabled = false)
            assertThat(off.tier).isEqualTo(TranslucentTier.FLAT)
            assertThat(off.samplesBackdrop).isFalse()
            assertThat(off.specularMotion).isFalse()
        }
    }

    @Test
    fun `high-contrast text uses the more opaque flat surface`() {
        val highContrast = policy(sdkInt = 36, highContrastText = true)

        assertThat(highContrast.tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(highContrast.highContrast).isTrue()
        assertThat(highContrast.samplesBackdrop).isFalse()
        assertThat(policy(sdkInt = 36).highContrast).isFalse()
    }

    @Test
    fun `remove animations keeps the translucency but stops the specular rim from moving`() {
        assertThat(policy(sdkInt = 36).specularMotion).isTrue()

        val reduced = policy(sdkInt = 36, reducedMotion = true)

        assertThat(reduced.tier).isEqualTo(TranslucentTier.LIQUID)
        assertThat(reduced.specularMotion).isFalse()
    }

    @Test
    fun `only the refracting translucency has a specular rim that can move`() {
        assertThat(policy(sdkInt = 32).specularMotion).isFalse()
        assertThat(policy(sdkInt = 29).specularMotion).isFalse()
    }

    @Test
    fun `a device ceiling lowers the translucency and says so`() {
        val frosted = policy(sdkInt = 36, ceiling = TranslucentTier.FROSTED)
        assertThat(frosted.tier).isEqualTo(TranslucentTier.FROSTED)
        assertThat(frosted.limited).isTrue()
        assertThat(frosted.specularMotion).isFalse()
        assertThat(frosted.samplesBackdrop).isTrue()

        val flat = policy(sdkInt = 36, ceiling = TranslucentTier.FLAT)
        assertThat(flat.tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(flat.limited).isTrue()
        assertThat(flat.samplesBackdrop).isFalse()

        assertThat(policy(sdkInt = 32, ceiling = TranslucentTier.FLAT).limited).isTrue()
    }

    @Test
    fun `a ceiling never raises the translucency and only counts when it is what lowered it`() {
        assertThat(policy(sdkInt = 32, ceiling = TranslucentTier.LIQUID).tier).isEqualTo(TranslucentTier.FROSTED)
        assertThat(policy(sdkInt = 32, ceiling = TranslucentTier.FROSTED).limited).isFalse()
        assertThat(policy(sdkInt = 30, ceiling = TranslucentTier.FLAT).limited).isFalse()
        assertThat(policy(sdkInt = 36).limited).isFalse()
    }

    @Test
    fun `the switch and high-contrast text still win over the ceiling`() {
        val off = policy(sdkInt = 36, enabled = false, ceiling = TranslucentTier.FROSTED)
        assertThat(off.tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(off.limited).isFalse()

        val contrast = policy(sdkInt = 36, highContrastText = true, ceiling = TranslucentTier.FROSTED)
        assertThat(contrast.tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(contrast.limited).isFalse()
    }

    @Test
    fun `stepping down goes liquid, frosted, flat and stays flat`() {
        assertThat(TranslucentTier.LIQUID.stepDown()).isEqualTo(TranslucentTier.FROSTED)
        assertThat(TranslucentTier.FROSTED.stepDown()).isEqualTo(TranslucentTier.FLAT)
        assertThat(TranslucentTier.FLAT.stepDown()).isEqualTo(TranslucentTier.FLAT)
    }

    @Test
    fun `the default policy, used before settings are read, is flat`() {
        assertThat(TranslucentPolicy.Flat.tier).isEqualTo(TranslucentTier.FLAT)
        assertThat(TranslucentPolicy.Flat.specularMotion).isFalse()
    }

    @Test
    fun `the specular light angle follows motion only when allowed`() {
        assertThat(TranslucentPolicy.specularAngle(shift = 1f, motion = true)).isEqualTo(TranslucentPolicy.REST_LIGHT_ANGLE + TranslucentPolicy.LIGHT_SWING)
        assertThat(TranslucentPolicy.specularAngle(shift = -1f, motion = true)).isEqualTo(TranslucentPolicy.REST_LIGHT_ANGLE - TranslucentPolicy.LIGHT_SWING)
        assertThat(TranslucentPolicy.specularAngle(shift = 5f, motion = true)).isEqualTo(TranslucentPolicy.REST_LIGHT_ANGLE + TranslucentPolicy.LIGHT_SWING)
        assertThat(TranslucentPolicy.specularAngle(shift = 1f, motion = false)).isEqualTo(TranslucentPolicy.REST_LIGHT_ANGLE)
        assertThat(TranslucentPolicy.specularAngle(shift = Float.NaN, motion = true)).isEqualTo(TranslucentPolicy.REST_LIGHT_ANGLE)
    }
}
