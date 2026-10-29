package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueAction
import io.sonora.feature.library.ui.confirmationText
import io.sonora.feature.library.ui.songCount
import org.junit.Test

class LibraryMenuTextTest {

    private val en = XmlStrings.of()

    @Test
    fun `queueing actions confirm, playing now does not`() {
        assertThat(QueueAction.PLAY_NEXT.confirmationText(en)).isEqualTo("Playing Next")
        assertThat(QueueAction.ADD_TO_QUEUE.confirmationText(en)).isEqualTo("Added to Queue")
        assertThat(QueueAction.PLAY_NOW.confirmationText(en)).isNull()
    }

    @Test
    fun `song counts read naturally`() {
        assertThat(songCount(0, en)).isEqualTo("No Songs")
        assertThat(songCount(1, en)).isEqualTo("1 Song")
        assertThat(songCount(1_260, en)).isEqualTo("1,260 Songs")
    }

    @Test
    fun `song counts follow the language, numbers included`() {
        assertThat(songCount(1_260, XmlStrings.of("-es"))).isEqualTo("1.260 canciones")
        assertThat(songCount(1, XmlStrings.of("-es"))).isEqualTo("1 canción")
        assertThat(songCount(3, XmlStrings.of("-eu"))).isEqualTo("3 abesti")
    }

    @Test
    fun `the export to rekordbox is worded with the brand in lowercase`() {
        val es = XmlStrings.of("-es")
        val eu = XmlStrings.of("-eu")

        assertThat(en.get(R.string.menu_export_rekordbox)).isEqualTo("Export to rekordbox")
        assertThat(es.get(R.string.menu_export_rekordbox)).isEqualTo("Exportar a rekordbox")
        assertThat(eu.get(R.string.menu_export_rekordbox)).isEqualTo("Esportatu rekordboxera")
        assertThat(en.get(R.string.notice_export_queued)).isEqualTo("Added to the Export tab as a rekordbox playlist")
        assertThat(es.get(R.string.notice_export_queued)).isEqualTo("Añadida a la pestaña Exportar como lista de rekordbox")
        assertThat(eu.get(R.string.notice_export_queued)).isEqualTo("Esportatu fitxara gehitu da, rekordbox zerrenda gisa")
    }
}
