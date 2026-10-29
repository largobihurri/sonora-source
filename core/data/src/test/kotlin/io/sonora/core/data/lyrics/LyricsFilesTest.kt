package io.sonora.core.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.lyrics.LyricLine
import org.junit.Test

/** Every lyric text below is invented for the tests. */
class LyricsFilesTest {

    @Test
    fun `the sidecar is the lrc file with the song's base name, any case`() {
        val names = listOf("01 Test Song.flac", "cover.jpg", "01 test song.LRC", "02 Other.lrc")

        assertThat(LyricsFiles.sidecarName("01 Test Song.flac", names)).isEqualTo("01 test song.LRC")
    }

    @Test
    fun `an exact name wins over a case variant`() {
        val names = listOf("a.mp3", "A.lrc", "a.lrc")

        assertThat(LyricsFiles.sidecarName("a.mp3", names)).isEqualTo("a.lrc")
    }

    @Test
    fun `no lrc with the song's name means no sidecar`() {
        assertThat(LyricsFiles.sidecarName("a.mp3", listOf("a.mp3", "a.txt", "ab.lrc", "a.mp3.lrc.bak"))).isNull()
        assertThat(LyricsFiles.sidecarName("noextension", listOf("noextension.lrc"))).isEqualTo("noextension.lrc")
    }

    @Test
    fun `utf-8 text decodes with or without a byte order mark`() {
        val text = "[00:01.00]Prueba única línea"

        assertThat(LyricsFiles.decode(text.toByteArray(Charsets.UTF_8))).isEqualTo(text)
        assertThat(LyricsFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())).isEqualTo(text)
    }

    @Test
    fun `utf-16 text with a byte order mark decodes`() {
        val text = "[00:01.00]Test line ñ"

        assertThat(LyricsFiles.decode(text.toByteArray(Charsets.UTF_16))).isEqualTo(text) // big endian with BOM
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertThat(LyricsFiles.decode(little)).isEqualTo(text)
    }

    @Test
    fun `text that is not utf-8 is read as windows latin`() {
        val text = "[00:01.00]Línea de prueba"

        assertThat(LyricsFiles.decode(text.toByteArray(charset("windows-1252")))).isEqualTo(text)
    }

    private fun sylt(vararg entries: Pair<String, Int>, terminator: ByteArray = byteArrayOf(0), charset: String = "ISO-8859-1") =
        entries.fold(ByteArray(0)) { bytes, (text, ms) ->
            bytes + text.toByteArray(charset(charset)) + terminator +
                byteArrayOf((ms ushr 24).toByte(), (ms ushr 16).toByte(), (ms ushr 8).toByte(), ms.toByte())
        }

    @Test
    fun `synchronised lyrics frames decode to sorted lines`() {
        val data = sylt("Second test line" to 4_000, "\nFirst test line" to 1_500, "" to 6_000)

        assertThat(LyricsFiles.syltLines(data, encoding = 0)).containsExactly(
            LyricLine(1_500, "First test line"),
            LyricLine(4_000, "Second test line"),
            LyricLine(6_000, ""),
        ).inOrder()
    }

    @Test
    fun `synchronised lyrics in utf-8 and utf-16 decode`() {
        val utf8 = sylt("Línea uno" to 1_000, charset = "UTF-8")
        assertThat(LyricsFiles.syltLines(utf8, encoding = 3)).containsExactly(LyricLine(1_000, "Línea uno"))

        val utf16 = sylt("Line one" to 1_000, "Line two" to 2_000, terminator = byteArrayOf(0, 0), charset = "UTF-16")
        assertThat(LyricsFiles.syltLines(utf16, encoding = 1).map { it.text }).containsExactly("Line one", "Line two").inOrder()
    }

    @Test
    fun `a truncated synchronised lyrics frame keeps its complete entries`() {
        val data = sylt("First test line" to 1_000) + "Cut".toByteArray() + byteArrayOf(0, 0, 0)

        assertThat(LyricsFiles.syltLines(data, encoding = 0)).containsExactly(LyricLine(1_000, "First test line"))
    }
}
