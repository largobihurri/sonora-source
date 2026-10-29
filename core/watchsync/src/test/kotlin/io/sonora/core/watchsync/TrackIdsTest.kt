package io.sonora.core.watchsync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackIdsTest {

    @Test
    fun `an id is 16 lowercase hex characters`() {
        val id = TrackIds.of("content://media/42", "watch-opus")

        assertThat(id).matches("[0-9a-f]{16}")
    }

    @Test
    fun `the same source and profile always give the same id`() {
        assertThat(TrackIds.of("content://media/42", "watch-opus"))
            .isEqualTo(TrackIds.of("content://media/42", "watch-opus"))
    }

    @Test
    fun `a different source or profile gives a different id`() {
        val base = TrackIds.of("content://media/42", "watch-opus")

        assertThat(TrackIds.of("content://media/43", "watch-opus")).isNotEqualTo(base)
        assertThat(TrackIds.of("content://media/42", "watch-opus-256")).isNotEqualTo(base)
    }

    @Test
    fun `source and profile cannot be shifted into each other`() {
        assertThat(TrackIds.of("ab", "c")).isNotEqualTo(TrackIds.of("a", "bc"))
    }

    @Test
    fun `hex ids of 8 to 64 characters are valid`() {
        assertThat(TrackIds.isValid("0123abcd")).isTrue()
        assertThat(TrackIds.isValid("a".repeat(64))).isTrue()
    }

    @Test
    fun `anything that could leave the music folder or is not lowercase hex is invalid`() {
        listOf("", "abc", "a".repeat(65), "../etc/passwd", "ab/cd/ef/01", "ABCDEF01", "0123abcg", "0123abcd.opus", "..").forEach {
            assertThat(TrackIds.isValid(it)).isFalse()
        }
    }
}
