import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/** Name of the signing config shared by every Bihurri app. */
private const val BIHURRI_SIGNING_CONFIG = "bihurri"

/** Gradle property that overrides where `signing.properties` is read from. */
private const val BIHURRI_SIGNING_PROPERTY = "bihurri.signing.properties"

/**
 * Signs every build type with the common Bihurri key (the one Kora's `signature` permission relies on).
 *
 * The credentials come from `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), found at the
 * Gradle property `bihurri.signing.properties` or else `~/.bihurri/signing/signing.properties`. The file and the keystore
 * live outside the repository and are never copied or printed. When the file is missing or incomplete the build keeps the
 * default debug key (every build type except `release`) and logs a warning: it still builds, but that APK cannot join
 * the Kora mesh.
 *
 * Call it from an application convention plugin; the `configureEach` callback also reaches build types the module
 * declares later, so a module must not set `signingConfig` itself.
 */
fun Project.configureBihurriSigning(extension: ApplicationExtension) {
    val override = providers.gradleProperty(BIHURRI_SIGNING_PROPERTY).orNull
    val file = if (override != null) File(override) else File(System.getProperty("user.home"), ".bihurri/signing/signing.properties")

    val properties = Properties()
    val loaded = file.isFile && file.canRead() && runCatching { file.inputStream().use(properties::load) }.isSuccess
    fun value(key: String) = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    val storeFile = value("storeFile")
    val storePassword = value("storePassword")
    val keyAlias = value("keyAlias")
    val keyPassword = value("keyPassword")

    if (!loaded || storeFile == null || storePassword == null || keyAlias == null || keyPassword == null) {
        logger.warn(
            "Bihurri signing: no usable signing.properties at ${file.path}; $path falls back to the debug key " +
                "and cannot join the Kora mesh.",
        )
        // Without the key the release-like build types (benchmark, nightly) still need a signature to install, so they
        // keep signing with the debug key as they did before the common key; `release` stays unsigned.
        extensions.getByType(ApplicationAndroidComponentsExtension::class.java).finalizeDsl { dsl ->
            dsl.buildTypes.filter { it.name != "release" && it.signingConfig == null }.forEach {
                it.signingConfig = dsl.signingConfigs.getByName("debug")
            }
        }
        return
    }

    extension.signingConfigs.create(BIHURRI_SIGNING_CONFIG) {
        this.storeFile = File(storeFile).let { if (it.isAbsolute) it else File(file.parentFile, storeFile) }
        this.storePassword = storePassword
        this.keyAlias = keyAlias
        this.keyPassword = keyPassword
    }
    extension.buildTypes.configureEach {
        signingConfig = extension.signingConfigs.getByName(BIHURRI_SIGNING_CONFIG)
    }
}
