plugins {
    alias(libs.plugins.sonora.jvm.library)
}

dependencies {
    // The domain ports and models appear in the dispatcher's public signatures.
    api(projects.core.domain)
    // DjModeSettings lives with the DJ model.
    api(projects.core.djmodel)
    api(libs.kotlinx.coroutines.core)
}
