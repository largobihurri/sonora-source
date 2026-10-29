package io.sonora.core.domain.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StickPathsTest {

    @Test
    fun `characters FAT forbids become underscores`() {
        assertThat(FatNames.component("AC/DC: \"Live\" <1991>?", "x")).isEqualTo("AC_DC_ _Live_ _1991__")
        assertThat(FatNames.component("Tab\there|*\\", "x")).isEqualTo("Tab here___")
    }

    @Test
    fun `trailing dots and spaces, leading dots and blank names are not kept`() {
        assertThat(FatNames.component("  Mr. Oizo...  ", "x")).isEqualTo("Mr. Oizo")
        assertThat(FatNames.component(".hidden", "x")).isEqualTo("_hidden")
        assertThat(FatNames.component(" ... ", "UnknownArtist")).isEqualTo("UnknownArtist")
        assertThat(FatNames.component(null, "UnknownAlbum")).isEqualTo("UnknownAlbum")
    }

    @Test
    fun `DOS device names are prefixed`() {
        assertThat(FatNames.component("CON", "x")).isEqualTo("_CON")
        assertThat(FatNames.component("com1", "x")).isEqualTo("_com1")
        assertThat(FatNames.fileName("aux.aiff")).isEqualTo("_aux.aiff")
        assertThat(FatNames.component("Console", "x")).isEqualTo("Console")
    }

    @Test
    fun `long names are cut to the limit without splitting a surrogate pair`() {
        assertThat(FatNames.component("a".repeat(100), "x")).hasLength(FatNames.MAX_FOLDER_CHARS)
        val emoji = "a".repeat(63) + "🎵" + "b"
        assertThat(FatNames.component(emoji, "x")).isEqualTo("a".repeat(63))
    }

    @Test
    fun `long file names keep their extension`() {
        val name = FatNames.fileName("x".repeat(200) + ".aiff")

        assertThat(name).hasLength(FatNames.MAX_FILE_CHARS)
        assertThat(name).endsWith("x.aiff")
        assertThat(FatNames.fileName(":.aiff")).isEqualTo("_.aiff")
        assertThat(FatNames.fileName(".aiff")).isEqualTo("_.aiff")
    }

    @Test
    fun `tracks go to Contents, artist, album, with rekordbox's fallbacks`() {
        val planner = StickPathPlanner()

        assertThat(planner.plan("Daft Punk", "Discovery", "01 One More Time.aiff"))
            .isEqualTo("/Contents/Daft Punk/Discovery/01 One More Time.aiff")
        assertThat(planner.plan(null, " ", "b.aiff")).isEqualTo("/Contents/UnknownArtist/UnknownAlbum/b.aiff")
    }

    @Test
    fun `a taken file name gets a number, ignoring case`() {
        val planner = StickPathPlanner(listOf("/Contents/A/B/track.aiff"))

        assertThat(planner.plan("A", "B", "TRACK.aiff")).isEqualTo("/Contents/A/B/TRACK (2).aiff")
        assertThat(planner.plan("A", "B", "track.aiff")).isEqualTo("/Contents/A/B/track (3).aiff")
    }

    @Test
    fun `files already on the stick are never planned over`() {
        val onStick = setOf("/Contents/A/B/track.aiff".uppercase())
        val planner = StickPathPlanner()

        assertThat(planner.plan("A", "B", "track.aiff") { it.uppercase() in onStick }).isEqualTo("/Contents/A/B/track (2).aiff")
    }

    @Test
    fun `folders keep the spelling they already have`() {
        val planner = StickPathPlanner(listOf("/Contents/ABBA/Gold/a.aiff"))

        assertThat(planner.plan("Abba", "GOLD", "b.aiff")).isEqualTo("/Contents/ABBA/Gold/b.aiff")
        assertThat(planner.plan("abba", "Arrival", "c.aiff")).isEqualTo("/Contents/ABBA/Arrival/c.aiff")
    }

    @Test
    fun `planned paths stay within the length limit, numbered or not`() {
        val planner = StickPathPlanner()
        val long = "w".repeat(300)

        val first = planner.plan(long, long, "$long.aiff")
        val second = planner.plan(long, long, "$long.aiff")

        assertThat(first.length).isAtMost(StickPathPlanner.MAX_PATH_CHARS)
        assertThat(second.length).isAtMost(StickPathPlanner.MAX_PATH_CHARS)
        assertThat(second).endsWith(" (2).aiff")
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `a taken name whose only dot is early is numbered like the rest of the name, not cut before it`() {
        val name = "a." + "x".repeat(200)
        val planner = StickPathPlanner()

        val first = planner.plan("A", "B", name)
        val second = planner.plan("A", "B", name)
        val third = planner.plan("A", "B", "b.c" + "y".repeat(20))

        assertThat(first.substringAfterLast('/')).hasLength(FatNames.MAX_FILE_CHARS)
        assertThat(second.substringAfterLast('/')).hasLength(FatNames.MAX_FILE_CHARS)
        assertThat(second).endsWith("x (2)")
        assertThat(second).isNotEqualTo(first)
        assertThat(third).endsWith("/b.c" + "y".repeat(20))
        assertThat(planner.plan("A", "B", "b.c" + "y".repeat(20))).endsWith("/b.c" + "y".repeat(20) + " (2)")
    }

    @Test
    fun `a short extension stays after the number`() {
        val planner = StickPathPlanner(listOf("/Contents/A/B/mix.v2.aiff"))

        assertThat(planner.plan("A", "B", "mix.v2.aiff")).isEqualTo("/Contents/A/B/mix.v2 (2).aiff")
    }
}
