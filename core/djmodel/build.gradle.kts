plugins {
    alias(libs.plugins.sonora.jvm.library)
}

dependencies {
    // MusicalKey and BeatGrid appear in the DJ model's public types.
    api(projects.core.domain)
    api(libs.kotlinx.coroutines.core)
}
