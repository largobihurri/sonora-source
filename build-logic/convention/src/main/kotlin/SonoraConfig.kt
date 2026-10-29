import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal object SonoraConfig {
    // Current stable AndroidX (Compose 1.12, core 1.19, lifecycle 2.11, navigation3 1.2) requires compileSdk 37.
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 29
    val JAVA_VERSION = JavaVersion.VERSION_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Shared Android settings for application and library modules. */
internal fun configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = SonoraConfig.COMPILE_SDK
        defaultConfig.minSdk = SonoraConfig.MIN_SDK
        compileOptions.sourceCompatibility = SonoraConfig.JAVA_VERSION
        compileOptions.targetCompatibility = SonoraConfig.JAVA_VERSION
        testOptions.unitTests.isReturnDefaultValues = true
    }
}
