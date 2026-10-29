package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * A pure guard against the clipping the D3.1 device review found (a Spanish 412 dp phone: "Cuadrícula d...", "Anchc"):
 * the text of the narrow slots of the DJ screen has a length budget in every language, read from the resource files.
 *
 * The budgets come from the widths the slots really have, at about 6.6 dp per character at 12 sp and 9.6 dp at 16 sp
 * (Roboto, mixed case) and a user font scale up to 1.3:
 * - a tempo range segment (a quarter of ~196 dp, minus 8 dp of padding: ~41 dp): 4 characters, 4 x 6.6 x 1.3 = 34 dp;
 * - the grid editor's title (the header row minus Done and the "Edited" chip: ~170 dp): 12 characters,
 *   12 x 9.6 x 1.3 = 150 dp, and it shrinks (down to 12 sp) beyond that instead of being cut;
 * - a bar or beat button (a weight 1 of 4.4 in 356 dp: ~77 dp, minus 12 dp of padding: ~65 dp): 10 characters at
 *   their 14 sp, which then shrink (down to 10 sp) instead of being cut.
 */
class DjStringFitTest {

    private val locales = listOf("values", "values-es", "values-eu")

    private fun string(folder: String, file: String, name: String): String {
        val xml = File("src/main/res/$folder/$file").readText()
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(xml)
        return requireNotNull(match) { "$name missing in $folder/$file" }.groupValues[1]
    }

    @Test
    fun `a tempo range label fits its segment`() {
        for (folder in locales) {
            val label = string(folder, "dj_tempo_strings.xml", "dj_tempo_range_wide")
            assertThat(label.length).isAtMost(4)
        }
    }

    @Test
    fun `the grid editor title fits next to the edited marker and Done`() {
        for (folder in locales) {
            val title = string(folder, "dj_grid_strings.xml", "dj_grid_title")
            assertThat(title.length).isAtMost(12)
        }
    }

    @Test
    fun `the beat and bar buttons fit their share of the row`() {
        for (folder in locales) {
            for (name in listOf("dj_grid_beat_earlier", "dj_grid_beat_later", "dj_grid_bar_earlier", "dj_grid_bar_later")) {
                assertThat(string(folder, "dj_grid_strings.xml", name).length).isAtMost(10)
            }
        }
    }

    @Test
    fun `the phrase caption fits the middle of its row`() {
        // About 185 dp at 13 sp (~7 dp a character, x 1.3): 18 characters; it shrinks down to 10 sp beyond that.
        for (folder in locales) {
            assertThat(string(folder, "dj_grid_strings.xml", "dj_grid_phrase_start").length).isAtMost(18)
        }
    }

    @Test
    fun `the pad mode switch labels fit their 40 dp segments`() {
        // 12 sp semibold, about 6.6 dp a character, x 1.3 for the largest font: 5 characters are 43 dp of a 40 dp segment
        // that has 2 dp of padding each side, and the text shrinks (down to 10 sp) beyond 4.
        for (folder in locales) {
            for (name in listOf("dj_loop_mode_cues", "dj_loop_mode_loop")) {
                assertThat(string(folder, "dj_loop_strings.xml", name).length).isAtMost(5)
            }
        }
    }

    @Test
    fun `the loop transport labels fit their share of the row`() {
        // The narrowest buttons are ~49 dp (IN, OUT; 1/2X and 2X are fixed text) at 13 sp bold (~7.6 dp a character): 5 characters, and the
        // Exit / Reloop toggle has ~74 dp: 8.
        for (folder in locales) {
            for (name in listOf("dj_loop_in", "dj_loop_out")) {
                assertThat(string(folder, "dj_loop_strings.xml", name).length).isAtMost(5)
            }
            for (name in listOf("dj_loop_exit", "dj_loop_reloop")) {
                assertThat(string(folder, "dj_loop_strings.xml", name).length).isAtMost(8)
            }
        }
    }

    @Test
    fun `the beats unit and the loop memory button fit`() {
        // The stepper's value cell is ~84 dp: the value (up to "1/32") at 15 sp and the unit at 10 sp, 7 characters.
        // "+ Memory" is 83 dp wide at 13 sp (~7 dp a character): 9 characters, and the loop variant 8.
        for (folder in locales) {
            assertThat(string(folder, "dj_loop_strings.xml", "dj_loop_beats_unit").length).isAtMost(7)
            assertThat(string(folder, "dj_loop_strings.xml", "dj_loop_memory_add").length).isAtMost(8)
            assertThat(string(folder, "dj_cue_strings.xml", "dj_cue_memory_add").length).isAtMost(9)
        }
    }

    @Test
    fun `the old analysis chip fits the time row, with the short form next to the edited badge`() {
        // The row's centre is ~224 dp on the narrowest phone, 11 sp text (~5.7 dp a character, x 1.3 for the largest font):
        // 26 characters alone (and the text shrinks beyond that); with the "Edited" badge beside it only the short form shows: 10.
        for (folder in locales) {
            assertThat(string(folder, "dj_analysis_strings.xml", "dj_old_analysis_chip").length).isAtMost(30)
            assertThat(string(folder, "dj_analysis_strings.xml", "dj_old_analysis_chip_short").length).isAtMost(10)
        }
    }

    @Test
    fun `the time row notices fit the centre of the row`() {
        // 12 sp (~6.6 dp a character, x 1.3), between the times: ~224 dp, 26 characters before the text is cut with an ellipsis.
        for (folder in locales) {
            for (name in listOf("dj_status_reanalysing", "dj_status_reanalysis_paused", "dj_status_reanalysis_failed")) {
                assertThat(string(folder, "dj_analysis_strings.xml", name).replace("%1\$s", "100 %").length).isAtMost(26)
            }
        }
    }

    @Test
    fun `the state card buttons fit the card`() {
        // A 32 dp capsule with 16 dp of padding at 14 sp semibold (~7.6 dp a character, x 1.3): 18 characters in ~220 dp.
        for (folder in locales) {
            for (name in listOf("dj_state_analyse_again", "dj_state_try_again")) {
                assertThat(string(folder, "dj_analysis_strings.xml", name).length).isAtMost(18)
            }
        }
    }

    @Test
    fun `the state card titles fit two lines of the card`() {
        // The card is ~260 dp wide: 20 characters a line at 16 sp semibold (~9.6 dp, x 1.3 is 12.5 dp); two lines, 40 characters.
        for (folder in locales) {
            for (name in listOf(
                "dj_state_waiting_title", "dj_state_analysing_title", "dj_state_paused_title", "dj_state_waveform_title",
                "dj_state_no_grid_title", "dj_state_failed_other", "dj_state_failed_unreadable", "dj_state_failed_unsupported",
                "dj_state_failed_corrupt", "dj_state_failed_too_short",
            )) {
                assertThat(string(folder, "dj_analysis_strings.xml", name).length).isAtMost(40)
            }
        }
    }
}
