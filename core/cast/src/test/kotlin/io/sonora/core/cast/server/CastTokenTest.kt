package io.sonora.core.cast.server

import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import org.junit.Test

class CastTokenTest {

    @Test
    fun `a token is 128 random bits written as lowercase hex`() {
        val token = CastToken.random(SecureRandom())
        assertThat(token.value).matches("[0-9a-f]{32}")
    }

    @Test
    fun `every session gets a different token`() {
        val random = SecureRandom()
        val tokens = List(100) { CastToken.random(random).value }.toSet()
        assertThat(tokens).hasSize(100)
    }

    @Test
    fun `only the exact token matches`() {
        val token = CastToken("0123456789abcdef0123456789abcdef")
        assertThat(token.matches("0123456789abcdef0123456789abcdef")).isTrue()
        assertThat(token.matches("0123456789abcdef0123456789abcdee")).isFalse()
        assertThat(token.matches("0123456789ABCDEF0123456789ABCDEF")).isFalse()
        assertThat(token.matches("0123456789abcdef")).isFalse()
        assertThat(token.matches("")).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short token is refused`() {
        CastToken("abc")
    }
}
