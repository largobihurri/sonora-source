package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectionTitleSizeTest {

    @Test
    fun `a title on one line, or wrapped at a space, breaks only between words`() {
        assertThat(breaksOnlyBetweenWords("Seleccionar canciones", listOf(0))).isTrue()
        assertThat(breaksOnlyBetweenWords("Seleccionar canciones", listOf(0, 12))).isTrue()
        assertThat(breaksOnlyBetweenWords("Hautatu abestiak", listOf(0, 8))).isTrue()
        assertThat(breaksOnlyBetweenWords("Select Songs", listOf(0, 7))).isTrue()
    }

    @Test
    fun `a line that starts inside a word splits it`() {
        // "Selecciona" / "r canciones": the device's 17 sp layout on a 320 dp phone.
        assertThat(breaksOnlyBetweenWords("Seleccionar canciones", listOf(0, 10))).isFalse()
        assertThat(breaksOnlyBetweenWords("Seleccionar canciones", listOf(0, 12, 17))).isFalse()
    }

    @Test
    fun `a line may start after a hyphen in the text`() {
        assertThat(breaksOnlyBetweenWords("Hi-Fi", listOf(0, 3))).isTrue()
    }

    @Test
    fun `the size steps down to the largest that fits`() {
        val tried = mutableListOf<Float>()
        val size = largestFittingSize(max = 17f, min = 12f, step = 1f) { tried += it; it <= 14.5f }
        assertThat(size).isEqualTo(14f)
        assertThat(tried).containsExactly(17f, 16f, 15f, 14f).inOrder()
    }

    @Test
    fun `the largest size is kept when it fits`() {
        assertThat(largestFittingSize(max = 17f, min = 12f, step = 0.25f) { true }).isEqualTo(17f)
    }

    @Test
    fun `the smallest size is the floor when nothing fits`() {
        val tried = mutableListOf<Float>()
        assertThat(largestFittingSize(max = 13f, min = 12f, step = 0.5f) { tried += it; false }).isEqualTo(12f)
        assertThat(tried).containsExactly(13f, 12.5f, 12f).inOrder()
    }

    @Test
    fun `a step that is not positive is refused`() {
        assertThrows(IllegalArgumentException::class.java) { largestFittingSize(max = 17f, min = 12f, step = 0f) { true } }
        assertThrows(IllegalArgumentException::class.java) { largestFittingSize(max = 17f, min = 12f, step = -1f) { true } }
    }

    @Test
    fun `the largest size that wraps between words wins over a larger one that splits a word`() {
        val size = wordSafeSize(max = 17f, min = 12f, step = 1f) { if (it >= 16f) TitleFit.SplitsWord else TitleFit.Fits }
        assertThat(size).isEqualTo(15f)
    }

    @Test
    fun `a word too wide at every size keeps the largest size that is not cut`() {
        // A long single-word translation at a large font scale: split at every size, overflowing above 14 sp.
        val tried = mutableListOf<Float>()
        val size = wordSafeSize(max = 17f, min = 12f, step = 1f) {
            tried += it
            if (it > 14f) TitleFit.Overflows else TitleFit.SplitsWord
        }
        assertThat(size).isEqualTo(14f)
        assertThat(tried).containsExactly(17f, 16f, 15f, 14f, 13f, 12f).inOrder()
    }

    @Test
    fun `the smallest size is the floor when every size is cut`() {
        assertThat(wordSafeSize(max = 13f, min = 12f, step = 0.5f) { TitleFit.Overflows }).isEqualTo(12f)
    }

    @Test
    fun `a word-safe step that is not positive is refused`() {
        assertThrows(IllegalArgumentException::class.java) { wordSafeSize(max = 17f, min = 12f, step = 0f) { TitleFit.Fits } }
    }
}
