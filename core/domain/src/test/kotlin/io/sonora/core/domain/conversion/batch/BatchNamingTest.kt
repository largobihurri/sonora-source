package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class BatchNamingTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun millis(dateTime: String, zone: ZoneId) = LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the batch folder is named after the local date and minute`() {
        assertThat(BatchNaming.batchFolderName(millis("2026-10-03T14:05:59", madrid), madrid)).isEqualTo("2026-10-03 14.05")
        assertThat(BatchNaming.batchFolderName(millis("2026-01-09T08:00:00", madrid), madrid)).isEqualTo("2026-01-09 08.00")
    }

    @Test
    fun `the batch folder follows the given time zone`() {
        val instant = millis("2026-10-03T23:30:00", ZoneOffset.UTC)
        assertThat(BatchNaming.batchFolderName(instant, ZoneOffset.UTC)).isEqualTo("2026-10-03 23.30")
        assertThat(BatchNaming.batchFolderName(instant, madrid)).isEqualTo("2026-10-04 01.30")
    }

    @Test
    fun `batch folder names hold no FAT-illegal characters`() {
        val name = BatchNaming.batchFolderName(millis("2026-12-31T23:59:00", madrid), madrid)
        assertThat(name).isEqualTo(BatchNaming.fatSafe(name))
        assertThat(name.none { it in "\"*/:<>?\\|" }).isTrue()
    }

    @Test
    fun `fatSafe replaces illegal characters and trims trailing dots and spaces`() {
        assertThat(BatchNaming.fatSafe("AC/DC: Back <In> Black?.aiff")).isEqualTo("AC_DC_ Back _In_ Black_.aiff")
        assertThat(BatchNaming.fatSafe("a\"b*c|d\\e\u0001f")).isEqualTo("a_b_c_d_e_f")
        assertThat(BatchNaming.fatSafe("Live... ")).isEqualTo("Live")
        assertThat(BatchNaming.fatSafe("Señor Ñandú – Café.aiff")).isEqualTo("Señor Ñandú – Café.aiff")
        assertThat(BatchNaming.fatSafe(" .. ")).isEqualTo("_")
        assertThat(BatchNaming.fatSafe("")).isEqualTo("_")
    }

    @Test
    fun `a free file name is kept`() {
        assertThat(BatchNaming.uniqueFileName("Song.aiff", listOf("Other.aiff", "Song.flac"))).isEqualTo("Song.aiff")
    }

    @Test
    fun `taken file names get the next free number before the extension`() {
        assertThat(BatchNaming.uniqueFileName("Song.aiff", listOf("Song.aiff"))).isEqualTo("Song (2).aiff")
        assertThat(BatchNaming.uniqueFileName("Song.aiff", listOf("Song.aiff", "Song (2).aiff", "Song (3).aiff")))
            .isEqualTo("Song (4).aiff")
        assertThat(BatchNaming.uniqueFileName("Song.aiff", listOf("Song.aiff", "Song (3).aiff"))).isEqualTo("Song (2).aiff")
    }

    @Test
    fun `file names collide ignoring case, as on FAT`() {
        assertThat(BatchNaming.uniqueFileName("Song.aiff", listOf("SONG.AIFF"))).isEqualTo("Song (2).aiff")
    }

    @Test
    fun `only the last extension is kept apart`() {
        assertThat(BatchNaming.uniqueFileName("Mr. Big.live.aiff", listOf("mr. big.live.aiff"))).isEqualTo("Mr. Big.live (2).aiff")
        assertThat(BatchNaming.uniqueFileName("README", listOf("README"))).isEqualTo("README (2)")
        assertThat(BatchNaming.uniqueFileName(".aiff", listOf(".aiff"))).isEqualTo(".aiff (2)")
    }

    @Test
    fun `folder names number the whole name, dots included`() {
        assertThat(BatchNaming.uniqueFolderName("2026-10-03 14.05", listOf("2026-10-03 14.05"))).isEqualTo("2026-10-03 14.05 (2)")
        assertThat(BatchNaming.uniqueFolderName("2026-10-03 14.05", emptyList())).isEqualTo("2026-10-03 14.05")
    }

    @Test
    fun `fatSafe shortens long names to FAT's limit, keeping the extension`() {
        val name = BatchNaming.fatSafe("a".repeat(300) + ".aiff")
        assertThat(name).hasLength(BatchNaming.MAX_NAME_BYTES)
        assertThat(name).endsWith("a.aiff")
        assertThat(BatchNaming.fatSafe("b".repeat(300) + ".aiff", maxBytes = 250)).hasLength(250)
    }

    @Test
    fun `shortening never splits a surrogate pair nor leaves a trailing dot or space`() {
        // "🎧" is two UTF-16 units; 126 of them plus "x" put a pair across the cut.
        val emoji = BatchNaming.fatSafe("x" + "🎧".repeat(126) + ".aiff")
        assertThat(emoji.length).isAtMost(BatchNaming.MAX_NAME_BYTES)
        assertThat(emoji.removeSuffix(".aiff").last().isHighSurrogate()).isFalse()
        assertThat(emoji).endsWith("🎧.aiff")

        val dotted = BatchNaming.fatSafe("a".repeat(248) + ". b.aiff", maxBytes = 254)
        assertThat(dotted).isEqualTo("a".repeat(248) + ".aiff")
    }

    @Test
    fun `numbered names stay within the limit`() {
        val desired = "c".repeat(250) + ".aiff"
        val numbered = BatchNaming.uniqueFileName(desired, listOf(desired))
        assertThat(numbered).hasLength(BatchNaming.MAX_NAME_BYTES)
        assertThat(numbered).endsWith("c (2).aiff")

        val tooLong = "d".repeat(300) + ".aiff"
        val first = BatchNaming.uniqueFileName(tooLong, emptyList(), maxBytes = 100)
        assertThat(first).hasLength(100)
        assertThat(BatchNaming.uniqueFileName(tooLong, listOf(first), maxBytes = 100)).isEqualTo("d".repeat(91) + " (2).aiff")
    }

    private fun bytes(name: String) = name.toByteArray(Charsets.UTF_8).size

    @Test
    fun `names fit 255 UTF-8 bytes, the limit of internal storage, not just 255 UTF-16 units`() {
        // ext4 and F2FS (internal storage) allow 255 bytes per name; "é" takes two, "の" three, "🎧" four.
        for (unit in listOf("é", "の", "🎧")) {
            val name = BatchNaming.fatSafe(unit.repeat(200) + ".aiff")
            assertThat(bytes(name)).isAtMost(255)
            assertThat(name).endsWith("$unit.aiff")
            assertThat(bytes(BatchNaming.fatSafe(unit.repeat(200) + ".aiff", 250))).isAtMost(250)
        }
        // As long as possible: one more character would not fit.
        val accented = BatchNaming.fatSafe("é".repeat(200) + ".aiff")
        assertThat(bytes(accented)).isAtLeast(254)

        val taken = BatchNaming.fatSafe("の".repeat(200) + ".aiff")
        val numbered = BatchNaming.uniqueFileName(taken, listOf(taken))
        assertThat(bytes(numbered)).isAtMost(255)
        assertThat(numbered).endsWith("の (2).aiff")
    }

    @Test
    fun `a numbered name stays within the limit even when the extension leaves no room for the number`() {
        val desired = "a." + "x".repeat(300)
        val first = BatchNaming.uniqueFileName(desired, emptyList())
        val second = BatchNaming.uniqueFileName(desired, listOf(first))

        assertThat(bytes(first)).isAtMost(255)
        assertThat(bytes(second)).isAtMost(255)
        assertThat(second.lowercase()).isNotEqualTo(first.lowercase())
        assertThat(second).endsWith(" (2)")

        val shortLimit = BatchNaming.uniqueFileName("track.aiff", listOf("track.aiff"), 16)
        assertThat(bytes(shortLimit)).isAtMost(16)
        assertThat(shortLimit).isNotEqualTo("track.aiff")
    }
}
