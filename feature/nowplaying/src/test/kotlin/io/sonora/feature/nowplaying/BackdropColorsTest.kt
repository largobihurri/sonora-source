package io.sonora.feature.nowplaying

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackdropColorsTest {

    private val plum = listOf(Color(0xFF5A2A40), Color(0xFF2C1A3A), Color(0xFF1C1C1E), Color(0xFF3A1A28))
    private val red = List(4) { Color(0xFFB0201A) }
    private val green = List(4) { Color(0xFF1A8030) }
    private val blue = List(4) { Color(0xFF1A30A0) }

    private fun List<State<Color>>.values() = map { it.value }

    /** How far the colour is from a grey: 0 for no hue at all. */
    private fun Color.chroma() = maxOf(red, green, blue) - minOf(red, green, blue)

    @Test
    fun `an artwork already shown opens on its colours, from the first frame`() = runTest {
        val cache = BackdropPaletteCache(capacity = 8).apply { this["a"] = red }
        val ui = HeadlessComposition(this)
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors("a", plum, cache) { awaitCancellation() } }

        assertThat(colors.values()).isEqualTo(red)
        ui.frame()
        assertThat(colors.values()).isEqualTo(red)
        ui.dispose()
    }

    @Test
    fun `a new artwork shows no fallback hue while its colours are extracted, then jumps to them`() = runTest {
        val cache = BackdropPaletteCache(capacity = 8)
        val extracted = CompletableDeferred<List<Color>?>()
        val ui = HeadlessComposition(this)
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors("a", plum, cache) { extracted.await() } }

        repeat(3) { ui.frame() }
        colors.values().forEach { assertThat(it.chroma()).isLessThan(0.03f) }

        extracted.complete(green)
        ui.frame()
        assertThat(colors.values()).isEqualTo(green)
        assertThat(cache["a"]).isEqualTo(green)
        ui.dispose()
    }

    @Test
    fun `a track change while shown cross-fades to the new colours`() = runTest {
        val palettes = mapOf("a" to red, "b" to blue)
        val cache = BackdropPaletteCache(capacity = 8)
        val ui = HeadlessComposition(this)
        var uri by mutableStateOf("a")
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors(uri, plum, cache) { palettes[it] } }
        ui.frame()
        assertThat(colors.values()).isEqualTo(red)

        uri = "b"
        repeat(10) { ui.frame() }
        val midway = colors.first().value
        assertThat(midway).isNotEqualTo(red.first())
        assertThat(midway).isNotEqualTo(blue.first())

        repeat(60) { ui.frame() }
        assertThat(colors.values()).isEqualTo(blue)
        ui.dispose()
    }

    @Test
    fun `a cached artwork is extracted again and cross-fades when its picture changed`() = runTest {
        val cache = BackdropPaletteCache(capacity = 8).apply { this["a"] = red }
        val extracted = CompletableDeferred<List<Color>?>()
        val ui = HeadlessComposition(this)
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors("a", plum, cache) { extracted.await() } }

        extracted.complete(green)
        repeat(10) { ui.frame() }
        assertThat(colors.first().value).isNotEqualTo(red.first())
        assertThat(colors.first().value).isNotEqualTo(green.first())
        assertThat(cache["a"]).isEqualTo(green)

        repeat(60) { ui.frame() }
        assertThat(colors.values()).isEqualTo(green)
        ui.dispose()
    }

    @Test
    fun `no artwork shows the fallback from the first frame`() = runTest {
        val ui = HeadlessComposition(this)
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors(null, plum, BackdropPaletteCache(capacity = 8)) { error("no artwork") } }

        assertThat(colors.values()).isEqualTo(plum)
        ui.dispose()
    }

    @Test
    fun `an artwork whose colours cannot be extracted shows the fallback at once`() = runTest {
        val cache = BackdropPaletteCache(capacity = 8)
        val ui = HeadlessComposition(this)
        var colors: List<State<Color>> = emptyList()
        ui.setContent { colors = rememberBackdropColors("a", plum, cache) { null } }
        ui.frame()

        assertThat(colors.values()).isEqualTo(plum)
        assertThat(cache["a"]).isNull()
        ui.dispose()
    }

    @Test
    fun `the cache keeps the most recently used artworks`() {
        val cache = BackdropPaletteCache(capacity = 2)
        cache["a"] = red
        cache["b"] = green
        cache["a"]
        cache["c"] = blue

        assertThat(cache["a"]).isEqualTo(red)
        assertThat(cache["b"]).isNull()
        assertThat(cache["c"]).isEqualTo(blue)
    }
}
