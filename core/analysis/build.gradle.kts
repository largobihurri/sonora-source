import com.android.build.api.variant.LibraryAndroidComponentsExtension
import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.analysis"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(projects.core.decoder)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Builds the analyser core with the host C++ compiler and runs its ctest suite (src/test/cpp); uses the SDK's cmake
 * (and its ninja) when none is on the PATH. Fails, naming the missing tool, when the machine has no cmake or no C++
 * compiler; `-Psonora.skipHostNativeTest=true` skips it on purpose (with a warning), e.g. on machines without one.
 */
abstract class HostNativeTest @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSourceDir: DirectoryProperty

    @get:Internal
    abstract val buildDir: DirectoryProperty

    @get:Internal
    abstract val sdkCmakeBinDir: DirectoryProperty

    @get:Input
    abstract val skip: Property<Boolean>

    @TaskAction
    fun run() {
        if (skip.get()) {
            logger.warn("hostNativeTest SKIPPED on request ($SKIP_PROPERTY=true): native analyser tests did not run")
            return
        }
        val sdkBin = sdkCmakeBinDir.orNull?.asFile
        val cmake = onPath("cmake") ?: sdkBin?.resolve("cmake")?.takeIf { it.canExecute() }
        val compiler = System.getenv("CXX")?.let { File(it) }?.takeIf { it.canExecute() }
            ?: listOf("c++", "clang++", "g++").firstNotNullOfOrNull(::onPath)
        if (cmake == null || compiler == null) {
            val missing = listOfNotNull(
                "cmake (on the PATH or the SDK's CMake 4.1.2)".takeIf { cmake == null },
                "a host C++ compiler (\$CXX, or c++, clang++ or g++ on the PATH)".takeIf { compiler == null },
            )
            throw GradleException(
                "hostNativeTest cannot run the native analyser tests: missing ${missing.joinToString(" and ")}. " +
                    "Install it, or pass -P$SKIP_PROPERTY=true to skip these tests on purpose.",
            )
        }
        val ninja = onPath("ninja") ?: cmake.parentFile.resolve("ninja").takeIf { it.canExecute() }
        val generator = if (ninja != null) {
            listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}")
        } else {
            emptyList()
        }
        val ctest = cmake.parentFile.resolve("ctest").takeIf { it.canExecute() } ?: onPath("ctest")
            ?: throw GradleException("ctest not found next to ${cmake.absolutePath}")
        val build = buildDir.get().asFile
        execOperations.exec {
            commandLine(
                listOf(cmake.absolutePath, "-S", sourceDir.get().asFile.absolutePath, "-B", build.absolutePath) +
                    generator + listOf("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}"),
            )
        }
        execOperations.exec { commandLine(cmake.absolutePath, "--build", build.absolutePath) }
        execOperations.exec { commandLine(ctest.absolutePath, "--test-dir", build.absolutePath, "--output-on-failure") }
    }

    private fun onPath(name: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }

    companion object {
        const val SKIP_PROPERTY = "sonora.skipHostNativeTest"
    }
}

val androidSdkDirectory = extensions.getByType<LibraryAndroidComponentsExtension>().sdkComponents.sdkDirectory

val hostNativeTest = tasks.register<HostNativeTest>("hostNativeTest") {
    group = "verification"
    description = "Builds the native analyser with the host compiler and runs its C++ tests."
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    testSourceDir.set(layout.projectDirectory.dir("src/test/cpp"))
    buildDir.set(layout.buildDirectory.dir("host-native"))
    sdkCmakeBinDir.set(androidSdkDirectory.map { it.dir("cmake/4.1.2/bin") })
    skip.set(providers.gradleProperty(HostNativeTest.SKIP_PROPERTY).map { it.toBoolean() }.orElse(false))
}

tasks.named("check") { dependsOn(hostNativeTest) }
