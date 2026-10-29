package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Runs the calibration end to end on ONE track of the user's rekordbox reference export. Skipped unless Gradle gets
 * `-Psonora.rekordboxReference=<export root>` and the Beat This! model was fetched (`tools/beatthis/fetch_model.py`),
 * so normal test runs never touch the user's music. Reports go to `build/calibration-smoke`.
 */
class CalibrationSmokeTest {

    @Test
    fun `one reference track is analysed and scored end to end`() {
        val root = System.getProperty("sonora.rekordboxReference")?.takeIf { it.isNotBlank() }
        assumeTrue("no -Psonora.rekordboxReference", root != null)
        val model = Paths.get(System.getProperty("sonora.beatThisModel") ?: "")
        assumeTrue("no Beat This! model at $model", Files.isRegularFile(model))
        val out = Paths.get(System.getProperty("sonora.calibrationOut"))

        val outcome = CalibrationRun(
            CalibrationOptions(
                reference = Paths.get(root!!),
                model = model,
                out = out,
                cache = out.resolve("cache"),
                limit = 1,
                threads = 1,
                label = "smoke",
                reanalyse = true,
            ),
        ) { println(it) }.execute()

        val result = outcome.results.single()
        assertThat(result.segments).isGreaterThan(0)
        assertThat(result.beat.referenceCount).isGreaterThan(0)
        assertThat(result.beat.fMeasure).isAtLeast(0.0)
        assertThat(result.analysisSeconds).isGreaterThan(0.0)
        for (file in outcome.files) assertThat(Files.size(file)).isGreaterThan(0L)
    }
}
