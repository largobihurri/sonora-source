package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class ArchivePasswordTest {

    @Test
    fun `generated passwords are long, from the unambiguous alphabet, and strong`() {
        repeat(200) {
            val password = PasswordGenerator.generate()
            assertThat(password.size).isEqualTo(PasswordGenerator.LENGTH)
            assertThat(password.all { it in PasswordGenerator.ALPHABET }).isTrue()
            assertThat(PasswordStrength.check(password)).isEqualTo(PasswordCheck.Strong)
        }
    }

    @Test
    fun `the alphabet leaves out look-alike characters`() {
        assertThat(PasswordGenerator.ALPHABET.none { it in "0O1lI" }).isTrue()
    }

    @Test
    fun `two passwords differ`() {
        assertThat(String(PasswordGenerator.generate())).isNotEqualTo(String(PasswordGenerator.generate()))
    }

    @Test
    fun `a generator with too few kinds in its first draw draws again`() {
        // A random source that first yields only lowercase letters, then anything.
        val skewed = object : Random() {
            var calls = 0
            override fun nextInt(bound: Int): Int = if (calls++ < PasswordGenerator.LENGTH) 30 else super.nextInt(bound)
        }
        val password = PasswordGenerator.generate(skewed)
        assertThat(PasswordStrength.check(password)).isEqualTo(PasswordCheck.Strong)
        assertThat(skewed.calls).isGreaterThan(PasswordGenerator.LENGTH)
    }

    @Test
    fun `a short password is weak, with the minimum length as the reason`() {
        assertThat(PasswordStrength.check("Ab3defgh")).isEqualTo(PasswordCheck.TooShort(PasswordStrength.MIN_LENGTH))
    }

    @Test
    fun `a password needs three kinds of characters`() {
        assertThat(PasswordStrength.check("abcdefghijklmnop"))
            .isEqualTo(PasswordCheck.TooFewKinds(required = PasswordStrength.MIN_KINDS, found = 1))
        assertThat(PasswordStrength.check("abcdefgh12345678"))
            .isEqualTo(PasswordCheck.TooFewKinds(required = PasswordStrength.MIN_KINDS, found = 2))
        assertThat(PasswordStrength.check("abcdefgh1234567!")).isEqualTo(PasswordCheck.Strong)
        assertThat(PasswordStrength.check("Abcdefgh12345678")).isEqualTo(PasswordCheck.Strong)
        assertThat(PasswordStrength.check("ñandú café 2026")).isEqualTo(PasswordCheck.Strong)
    }
}
