package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.setting.DevSettingWriter
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Writes sample device libraries under `build/pdb-out/<name>/PIONEER/` (stick layout: `rekordbox/export.pdb`,
 * `rekordbox/exportExt.pdb`, `DEVSETTING.DAT`), so they can be checked by hand with rekordcrate
 * (`rekordcrate dump-pdb -f json`, `list-playlists`, `dump-setting`); here only re-read with the test reader.
 */
class PdbOracleFilesTest {

    /** Holds the files when the build did not set `sonora.pdbOutDir` (e.g. an IDE run); deleted after the test. */
    @get:Rule
    val scratch = TemporaryFolder()

    private val outDir: File by lazy { System.getProperty("sonora.pdbOutDir")?.let(::File) ?: scratch.root }

    @Test
    fun `writes the empty, demo and 1000-track libraries in the stick layout`() {
        val libraries = mapOf(
            "empty" to DeviceLibrary(tracks = emptyList(), exportDate = LocalDate.of(2026, 10, 3)),
            "demo" to PdbDemoParityTest.demoLibrary(),
            "tracks1000" to SyntheticLibrary.build(1000),
        )
        for ((name, library) in libraries) {
            val pioneer = File(outDir, "$name/PIONEER").apply { File(this, "rekordbox").mkdirs() }
            val pdb = File(pioneer, "rekordbox/export.pdb").apply { writeBytes(PdbWriter.write(library)) }
            File(pioneer, "rekordbox/exportExt.pdb").writeBytes(PdbTemplates.exportExtPdb())
            File(pioneer, "DEVSETTING.DAT").writeBytes(DevSettingWriter.write())

            val read = PdbReader.read(pdb.readBytes())
            PdbReader.checkStructure(read)
            assertThat(read.rows(PdbTable.TRACKS)).hasSize(library.tracks.size)
        }
    }
}
