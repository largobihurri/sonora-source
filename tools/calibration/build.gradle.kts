plugins {
    alias(libs.plugins.sonora.jvm.library)
}

// Offline calibration tooling (D1.19 metrics, D1.20 runner). Pure JVM and NOT part of the app: nothing depends on it.
dependencies {
    api(projects.core.djmodel)
    api(projects.core.djengine)

    // D1.20: the ANLZ and export.pdb readers of D1.15 read the rekordbox reference export.
    implementation(testFixtures(projects.core.rekordbox))
    // D1.20: Beat This! small on the host CPU (the desktop build of ONNX Runtime; D1.21 ships the Android one).
    implementation(libs.onnxruntime)
}

// The reference export and the model live outside the repository; nothing here copies them.
val referenceRoot: Provider<String> = providers.gradleProperty("sonora.rekordboxReference")
val calibrationArgs: Provider<String> = providers.gradleProperty("sonora.calibrationArgs")
val modelFile = rootProject.layout.projectDirectory.file("tools/beatthis/build/small0.onnx")
val reportsDir = layout.buildDirectory.dir("reports/calibration")
val cacheDir = layout.buildDirectory.dir("calibration-cache")

tasks.test {
    // CalibrationSmokeTest runs only with -Psonora.rekordboxReference=<export root>; every other run skips it.
    referenceRoot.orNull?.let { systemProperty("sonora.rekordboxReference", it) }
    systemProperty("sonora.beatThisModel", modelFile.asFile.path)
    systemProperty("sonora.calibrationOut", layout.buildDirectory.dir("calibration-smoke").get().asFile.path)
    inputs.property("sonora.rekordboxReference", referenceRoot.orElse(""))
}

// The calibration run: `-Psonora.rekordboxReference=<export root> [-Psonora.calibrationArgs="--limit 5"]`. The CPU-capped
// way to run it is the installed distribution under systemd-run (see CalibrationMain); this task is the convenience.
tasks.register<JavaExec>("runCalibration") {
    group = "verification"
    description = "Runs the D1.20 calibration against a rekordbox reference export (reports in build/reports)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.sonora.tools.calibration.runner.CalibrationMain")
    maxHeapSize = "2g"
    argumentProviders += CommandLineArgumentProvider {
        val root = referenceRoot.orNull ?: error("Pass -Psonora.rekordboxReference=<export root>")
        listOf(
            "--reference", root,
            "--model", modelFile.asFile.path,
            "--out", reportsDir.get().asFile.path,
            "--cache", cacheDir.get().asFile.path,
        ) + (calibrationArgs.orNull?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
    }
}

// Writes the runtime classpath to a file so the run can go straight to `java` inside a CPU-capped systemd scope.
tasks.register("calibrationClasspath") {
    group = "verification"
    description = "Writes the calibration runtime classpath to build/calibration-classpath.txt."
    val runtime = sourceSets["main"].runtimeClasspath
    val out = layout.buildDirectory.file("calibration-classpath.txt")
    inputs.files(runtime)
    outputs.file(out)
    doLast { out.get().asFile.writeText(runtime.files.joinToString(File.pathSeparator)) }
}
