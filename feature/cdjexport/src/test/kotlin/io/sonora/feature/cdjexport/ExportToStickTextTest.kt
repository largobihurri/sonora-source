package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.StickIoException
import io.sonora.feature.cdjexport.export.DuplicateTrack
import io.sonora.feature.cdjexport.export.EjectScreen
import io.sonora.feature.cdjexport.export.EjectState
import io.sonora.feature.cdjexport.export.ExportJob
import io.sonora.feature.cdjexport.export.ExportPhase
import io.sonora.feature.cdjexport.export.ExportProgress
import io.sonora.feature.cdjexport.export.ExportResult
import io.sonora.feature.cdjexport.export.ExportedTrack
import io.sonora.feature.cdjexport.export.FailedTrack
import io.sonora.feature.cdjexport.export.StickLibrary
import io.sonora.feature.cdjexport.export.TrackFailure
import org.junit.Test

class ExportToStickTextTest {

    private val en = XmlStrings.of()

    @Test
    fun `each eject step is worded, with the stick's name when it is known`() {
        val stick = RemovableVolume("2C9C-2151", "SONORA", 16_000_000_000, RemovableVolume.State.MOUNTED)
        val es = XmlStrings.of("-es")

        assertThat(ejectMessage(EjectState.Idle, en)).isNull()
        assertThat(ejectMessage(EjectState.OpenScreen(stick, emptyList()), en)).isEqualTo("Opening Android's storage settings…")
        assertThat(ejectMessage(EjectState.Waiting(stick, EjectScreen.VOLUME_SETTINGS), en))
            .startsWith("Eject SONORA in Android's settings")
        assertThat(ejectMessage(EjectState.Waiting(null, EjectScreen.STORAGE_SETTINGS), en))
            .startsWith("Eject the stick in Android's settings")
        assertThat(ejectMessage(EjectState.Waiting(stick, null), en))
            .startsWith("Android's storage settings couldn't be opened.")
        assertThat(ejectMessage(EjectState.SafeToRemove("SONORA"), en)).isEqualTo("SONORA can be unplugged now.")
        assertThat(ejectMessage(EjectState.SafeToRemove(null), en)).isEqualTo("The stick can be unplugged now.")
        assertThat(ejectMessage(EjectState.SafeToRemove("SONORA"), es)).isEqualTo("Ya puedes desenchufar SONORA.")
    }

    @Test
    fun `a connected stick is named with its size, or alone when the size is unknown`() {
        val stick = RemovableVolume("2C9C-2151", "SONORA", 16_000_000_000, RemovableVolume.State.MOUNTED)

        assertThat(volumeSummary(stick, en)).isEqualTo("SONORA, 16.0 GB")
        assertThat(volumeSummary(stick.copy(totalBytes = null), en)).isEqualTo("SONORA")
        assertThat(en.get(R.string.cdj_stick_connected, volumeSummary(stick, en)))
            .startsWith("Connected stick: SONORA, 16.0 GB.")
    }

    @Test
    fun `an export that only completed an interrupted library says so`() {
        val result = ExportResult.Completed(emptyList(), emptyList(), emptyList(), null, 3, libraryWritten = true)

        val summary = summarize(result, en)

        assertThat(summary.title).isEqualTo("Library completed")
        assertThat(summary.message).startsWith("The stick's library was completed. It holds 3 tracks.")
        assertThat(summary.isError).isFalse()
    }

    @Test
    fun `an export that wrote nothing does not claim the library was completed`() {
        val result = ExportResult.Completed(emptyList(), emptyList(), emptyList(), null, 3, libraryWritten = false)

        val summary = summarize(result, en)

        assertThat(summary.title).isEqualTo("Nothing exported")
        assertThat(summary.message).isEqualTo("No track could be added; the stick was not changed.")
        assertThat(summary.isError).isTrue()
    }

    @Test
    fun `tracks already on the stick that join an existing playlist say the playlist was updated`() {
        val duplicates = listOf(DuplicateTrack("a.aiff", "/Contents/a.aiff"), DuplicateTrack("b.aiff", "/Contents/b.aiff"))
        val result = ExportResult.Completed(
            emptyList(), duplicates, emptyList(), "Set", 3, libraryWritten = true,
            playlistAdded = 2, playlistCreated = false,
        )

        val summary = summarize(result, en)

        assertThat(summary.title).isEqualTo("Playlist updated")
        assertThat(summary.message).startsWith("Added 2 tracks already on the stick to the playlist \"Set\".")
        assertThat(summary.isError).isFalse()
    }

    @Test
    fun `one track already on the stick joining a playlist is counted in the singular`() {
        val result = ExportResult.Completed(
            emptyList(), listOf(DuplicateTrack("a.aiff", "/Contents/a.aiff")), emptyList(), "Set", 3,
            libraryWritten = true, playlistAdded = 1, playlistCreated = false,
        )

        assertThat(summarize(result, en).message).startsWith("Added 1 track already on the stick to the playlist \"Set\".")
    }

    @Test
    fun `tracks already on the stick that make a new playlist say the playlist was added`() {
        val result = ExportResult.Completed(
            emptyList(), listOf(DuplicateTrack("a.aiff", "/Contents/a.aiff")), emptyList(), "Friday", 3,
            libraryWritten = true, playlistAdded = 1, playlistCreated = true,
        )

        val summary = summarize(result, en)

        assertThat(summary.title).isEqualTo("Playlist added")
        assertThat(summary.message).startsWith("Added 1 track already on the stick to the playlist \"Friday\".")
        assertThat(summary.isError).isFalse()
    }

    @Test
    fun `a failure that is not the stick's is not blamed on the stick`() {
        val summary = summarize(ExportResult.Failed("Starting the export", IllegalStateException("not allowed")), en)

        assertThat(summary.isError).isTrue()
        assertThat(summary.message).doesNotContain("stick failed")
        assertThat(summary.message).contains("as it was before this export")
    }

    @Test
    fun `a failure while the library was replaced says the next export completes it`() {
        val summary = summarize(ExportResult.Failed("Replacing export.pdb", StickIoException("gone"), libraryChanged = true), en)

        assertThat(summary.message).contains("export again")
        assertThat(summary.message).contains("completes")
    }

    @Test
    fun `summaries are translated with their counts`() {
        val result = ExportResult.Completed(
            listOf(ExportedTrack("a.aiff", "/Contents/a.aiff"), ExportedTrack("b.aiff", "/Contents/b.aiff")),
            emptyList(), emptyList(), "Set", 5, libraryWritten = true, playlistAdded = 2, playlistCreated = true,
        )

        assertThat(summarize(result, XmlStrings.of("-es")).message)
            .startsWith("Se han añadido 2 pistas a la lista «Set». La biblioteca de la memoria tiene ahora 5 pistas.")
        assertThat(summarize(result, XmlStrings.of("-eu")).title).isEqualTo("Esportazioa amaitu da")
    }

    @Test
    fun `skipped and failed tracks are listed with why`() {
        val result = ExportResult.Completed(
            emptyList(), listOf(DuplicateTrack("b.aiff", "/Contents/b.aiff")),
            listOf(FailedTrack("c.aiff", TrackFailure.NOT_ANALYSABLE, IllegalStateException())), null, 3, libraryWritten = false,
        )

        val summary = summarize(result, en)

        assertThat(summary.skipped).containsExactly("b.aiff: already on the stick")
        assertThat(summary.failed).containsExactly("c.aiff: no tempo or beat grid was found")
    }

    @Test
    fun `a track detail names its format, rate and size in the language's numbers`() {
        val file = SourceFile("u", "a.aiff", 35_200_000, SourceAudio(AudioContainer.AIFF, 16, 44_100, 2))

        assertThat(trackDetail(file, en)).isEqualTo("AIFF 16-bit · 44.1 kHz · 35.2 MB")
        assertThat(trackDetail(file, XmlStrings.of("-es"))).isEqualTo("AIFF 16 bits · 44,1 kHz · 35,2 MB")
    }

    @Test
    fun `a file that cannot be decoded says so, and the tracks to convert are counted`() {
        val profile = DeviceProfile.XDJ_700
        val es = XmlStrings.of("-es")
        val eu = XmlStrings.of("-eu")

        assertThat(RejectReason.Undecodable(AudioContainer.AIFF_C).message(profile, en))
            .isEqualTo("Sonora can't decode this audio (AIFF-C), so it can't convert it for the XDJ-700.")
        assertThat(conversionNotice(1, profile, en)).isEqualTo("1 file will be converted to AIFF 16-bit up to 48 kHz on export.")
        assertThat(conversionNotice(3, profile, en)).isEqualTo("3 files will be converted to AIFF 16-bit up to 48 kHz on export.")
        assertThat(conversionNotice(3, profile, es)).isEqualTo("Al exportar, 3 archivos se convertirán a AIFF de 16 bits, hasta 48 kHz.")
        assertThat(conversionNotice(3, profile, eu)).isEqualTo("Esportatzean, 3 fitxategi bihurtuko dira formatu honetara: AIFF, 16 bit, gehienez 48 kHz.")
        assertThat(exportIntro(profile, en)).doesNotContain("convert anything else first")
    }

    @Test
    fun `the stick's library is described`() {
        assertThat(StickLibrary.Empty.description(en)).isEqualTo("No rekordbox library yet: the export creates one.")
        assertThat(StickLibrary.Foreign.replaceWarning(en)).startsWith("This stick has a rekordbox library that Sonora didn't make.")
    }

    @Test
    fun `the export says Sonora is not affiliated with the player's makers, in every language`() {
        assertThat(notAffiliatedNotice(en)).isEqualTo(
            "Sonora is an independent app, not affiliated with, endorsed or sponsored by AlphaTheta or Pioneer DJ. " +
                "rekordbox, Pioneer DJ and XDJ are trademarks of their respective owners, named here only to describe compatibility.",
        )
        assertThat(notAffiliatedNotice(XmlStrings.of("-es"))).isEqualTo(
            "Sonora es una app independiente, sin relación con AlphaTheta ni Pioneer DJ, que no la patrocinan ni la respaldan. " +
                "rekordbox, Pioneer DJ y XDJ son marcas de sus respectivos propietarios, citadas aquí solo para indicar la compatibilidad.",
        )
        assertThat(notAffiliatedNotice(XmlStrings.of("-eu"))).isEqualTo(
            "Sonora aplikazio independentea da, eta ez dago AlphaTheta edo Pioneer DJ-rekin lotuta; haiek ez dute babesten ezta onartzen ere. " +
                "rekordbox, Pioneer DJ eta XDJ beren jabeen markak dira, eta bateragarritasuna adierazteko soilik aipatzen dira hemen.",
        )
    }

    @Test
    fun `rekordbox is written in lowercase in every string of the export`() {
        val values = listOf("", "-es", "-eu").flatMap { stringValues(java.io.File("src/main/res/values$it")) }

        assertThat(values).isNotEmpty()
        assertThat(values.filter { "Rekordbox" in it || "REKORDBOX" in it }).isEmpty()
        assertThat(XmlStrings.of("-eu").get(R.string.cdj_export_header)).isEqualTo("Esportatu rekordboxera")
        assertThat(XmlStrings.of("-es").get(R.string.cdj_notification_channel)).isEqualTo("Exportar a rekordbox")
    }

    /** Every `<string>` and plural item text in the XML files of [folder]. */
    private fun stringValues(folder: java.io.File): List<String> =
        folder.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            listOf("string", "item").flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { nodes.item(it).textContent }
            }
        }

    @Test
    fun `progress names the phase and the track`() {
        val running = ExportStep.Running("SONORA", 3, ExportProgress(1, 3, "b.aiff", ExportPhase.ANALYSING), cancelling = false)

        assertThat(exportProgressText(running, en)).isEqualTo("Analysing 2 of 3: b.aiff")
        val converting = running.copy(progress = ExportProgress(1, 3, "b.flac", ExportPhase.CONVERTING))
        assertThat(exportProgressText(converting, en)).isEqualTo("Converting 2 of 3: b.flac")
        assertThat(TrackFailure.NOT_CONVERTIBLE.message(en)).isEqualTo("the audio could not be decoded to convert it")
        assertThat(exportProgressText(running.copy(progress = null), en)).isEqualTo("Starting…")
        assertThat(exportProgressText(running.copy(cancelling = true), en)).isEqualTo("Cancelling…")
        assertThat(exportProgressText(running, XmlStrings.of("-eu"))).isEqualTo("2/3 aztertzen: b.aiff")
    }

    @Test
    fun `the notification names the stick and the track`() {
        val running = ExportJob.Running("SONORA", 3, ExportProgress(2, 3, "c.aiff", ExportPhase.COPYING))

        assertThat(exportNotificationTitle(running, en)).isEqualTo("Exporting to SONORA")
        assertThat(exportNotificationText(running, en)).isEqualTo("Track 3 of 3: c.aiff")
        assertThat(exportNotificationText(running.copy(progress = ExportProgress(3, 3, "", ExportPhase.WRITING_LIBRARY)), en))
            .isEqualTo("Track 3 of 3: writing the library")
        assertThat(exportNotificationText(ExportJob.Idle, en)).isEqualTo("Finishing…")
        assertThat(exportNotificationTitle(ExportJob.Idle, en)).isEqualTo("Exporting to rekordbox")
    }

    @Test
    fun `a stick without room says how much the export needs`() {
        assertThat(spaceProblemText(neededBytes = 2_000_000_000, freeBytes = 1_000_000_000, en))
            .isEqualTo("Not enough space: the export needs about 2.0 GB and the stick has 1.0 GB free.")
    }

    @Test
    fun `every kind of export end is worded in each language`() {
        val es = XmlStrings.of("-es")
        val eu = XmlStrings.of("-eu")
        val needsConfirmation = ExportResult.NeedsConfirmation(StickLibrary.Foreign)
        val stickFailed = ExportResult.Failed("Writing a.aiff", StickIoException("gone"))

        assertThat(summarize(needsConfirmation, en).title).isEqualTo("Nothing exported")
        assertThat(summarize(needsConfirmation, en).message).startsWith("The library on this stick would be replaced.")
        assertThat(summarize(needsConfirmation, es).message).startsWith("Se sustituiría la biblioteca de esta memoria.")
        assertThat(summarize(stickFailed, en).message).startsWith("The stick failed")
        assertThat(summarize(stickFailed, es).title).isEqualTo("La exportación ha fallado")
        assertThat(summarize(stickFailed, eu).message).startsWith("Memoriak huts egin du")
        assertThat(summarize(ExportResult.Cancelled, en).title).isEqualTo("Export cancelled")
        assertThat(summarize(ExportResult.Cancelled, en).isError).isFalse()
        assertThat(summarize(ExportResult.Cancelled, eu).title).isEqualTo("Esportazioa bertan behera utzi da")
        assertThat(summarize(ExportResult.Cancelled, es).message).startsWith("La biblioteca de la memoria está como antes;")
    }

    @Test
    fun `the notification follows the language it is given, cancelling and starting included`() {
        val es = XmlStrings.of("-es")
        val running = ExportJob.Running("SONORA", 3, ExportProgress(0, 3, "a.aiff", ExportPhase.COPYING))

        assertThat(exportNotificationTitle(running, es)).isNotEqualTo(exportNotificationTitle(running, en))
        assertThat(exportNotificationTitle(running, es)).contains("SONORA")
        assertThat(exportNotificationText(running, es)).isNotEqualTo(exportNotificationText(running, en))
        assertThat(exportNotificationText(running.copy(cancelling = true), en)).isEqualTo(en.get(R.string.cdj_cancelling))
        assertThat(exportNotificationText(running.copy(progress = null), en)).isEqualTo(en.get(R.string.cdj_starting))
        assertThat(exportNotificationText(running.copy(cancelling = true), es)).isEqualTo(es.get(R.string.cdj_cancelling))
    }
}

