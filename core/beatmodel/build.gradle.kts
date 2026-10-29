import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.beatmodel"
}

/**
 * Puts the Beat This! `small0.onnx` (10 MB, not committed) into the generated assets of every variant, as
 * `beatmodel/small0.onnx`. `tools/beatthis/model.json` is the single source of the URL, size and sha256.
 *
 * The file comes from `tools/beatthis/build/small0.onnx` when `fetch_model.py` already put it there (and its sha256
 * matches), otherwise it is downloaded. A sha256 or size mismatch fails the build: a model that is not the pinned one
 * must never ship. The task runs when assets are merged (packaging an APK, lint), never for unit tests, and it is up
 * to date once its output exists, so an offline rebuild does not need the network. With no network and no valid host
 * copy it fails with instructions, unless `-Psonora.beatmodel.optional=true` is passed (then the build goes on without
 * the model and the app falls back to qm-dsp).
 */
abstract class FetchBeatModel : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** `tools/beatthis/build/small0.onnx`, used instead of a download when present and valid. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostCopy: RegularFileProperty

    /** `-Psonora.beatmodel.optional=true`: when the download fails, warn and package no model (the app falls back to qm-dsp). */
    @get:Input
    abstract val optional: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val json = JsonSlurper().parse(manifest.get().asFile) as Map<*, *>
        val url = json["url"] as String
        val sha256 = json["sha256"] as String
        val size = (json["sizeBytes"] as Number).toLong()

        val root = outputDir.get().asFile
        root.deleteRecursively()
        val target = File(root, "beatmodel/small0.onnx")
        target.parentFile.mkdirs()

        val copy = hostCopy.orNull?.asFile
        if (copy != null && sha256Of(copy) == sha256 && copy.length() == size) {
            copy.copyTo(target)
            logger.lifecycle("Beat This! model: copied ${copy.path}")
            return
        }

        val partial = File(target.path + ".part")
        try {
            val actual = download(url, partial)
            if (actual != sha256 || partial.length() != size) {
                throw GradleException(
                    "The Beat This! model downloaded from $url is not the pinned one: sha256 $actual, ${partial.length()} bytes " +
                        "(expected $sha256, $size bytes, from tools/beatthis/model.json). The file was discarded.",
                )
            }
        } catch (e: GradleException) {
            partial.delete()
            throw e
        } catch (e: java.io.IOException) {
            partial.delete()
            val message = "Could not download the Beat This! model from $url (${e.message}). Run `python3 tools/beatthis/fetch_model.py` " +
                "once with network access (it puts the verified file in tools/beatthis/build/) and build again."
            if (!optional.get()) throw GradleException("$message Or pass -Psonora.beatmodel.optional=true to build without the model.", e)
            logger.warn("$message Building WITHOUT the model: the app will fall back to qm-dsp.")
            return
        }
        check(partial.renameTo(target)) { "Could not move ${partial.path} to ${target.path}" }
        logger.lifecycle("Beat This! model: downloaded $url")
    }

    private fun download(url: String, into: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        DigestInputStream(connection.getInputStream(), digest).use { input -> into.outputStream().use { input.copyTo(it) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                digest.update(block, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchBeatModel = tasks.register<FetchBeatModel>("fetchBeatModel") {
    description = "Provides the pinned Beat This! ONNX model as an app asset (checks the sha256 of tools/beatthis/model.json)."
    manifest.set(rootProject.layout.projectDirectory.file("tools/beatthis/model.json"))
    hostCopy.set(rootProject.layout.projectDirectory.file("tools/beatthis/build/small0.onnx").takeIf { it.asFile.isFile })
    optional.set(providers.gradleProperty("sonora.beatmodel.optional").map { it.toBoolean() }.orElse(false))
    outputDir.set(layout.buildDirectory.dir("generated/beatmodel/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchBeatModel, FetchBeatModel::outputDir)
    }
}

// The Android AAR and the host JAR carry the same Java classes but different native libraries; mixed on one JVM
// classpath the native loader can pick the wrong pairing and crash. Unit tests run on the host, so only the host JAR.
configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("RuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

dependencies {
    // BeatActivationModel, BeatActivationModelFactory and the types they carry are this module's public contract.
    api(projects.core.djmodel)
    // The full ONNX Runtime (MIT) for Android: the model needs Erf, Sin, Cos and ReduceL2, which a reduced-ops build lacks.
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.core)

    // The same ONNX Runtime Java API on the host JVM runs the real model in the unit tests.
    testImplementation(libs.onnxruntime)
    // LogMelSink rebuilds the log-mel spectrograms of the golden cases.
    testImplementation(projects.core.djengine)
}
