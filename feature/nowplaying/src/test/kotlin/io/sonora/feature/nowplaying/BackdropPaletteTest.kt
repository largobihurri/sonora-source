package io.sonora.feature.nowplaying

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The shell reads the playing track's backdrop colours ahead of Now Playing ([warmBackdropPalette]). */
class BackdropPaletteTest {

    private val grey = listOf(Color(0xFF808080), Color(0xFF606060), Color(0xFF707070), Color(0xFF505050))

    @Test
    fun `a track's colours are read ahead into the cache`() = runBlocking {
        val cache = BackdropPaletteCache(capacity = 2)

        warmBackdropPalette("a", cache) { grey }

        assertThat(cache["a"]).isEqualTo(grey)
    }

    @Test
    fun `colours already cached are not read again`() = runBlocking {
        val cache = BackdropPaletteCache(capacity = 2).apply { this["a"] = grey }

        warmBackdropPalette("a", cache) { error("read again") }

        assertThat(cache["a"]).isEqualTo(grey)
    }

    @Test
    fun `nothing is stored for a track without artwork or a picture that cannot be read`() = runBlocking {
        val cache = BackdropPaletteCache(capacity = 2)

        warmBackdropPalette(null, cache) { error("no artwork") }
        warmBackdropPalette("b", cache) { null }

        assertThat(cache["b"]).isNull()
    }
}
