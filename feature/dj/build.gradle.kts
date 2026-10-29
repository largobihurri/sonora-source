plugins {
    alias(libs.plugins.sonora.android.library.compose)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.feature.dj"
}

dependencies {
    // DjBeatGrid and BandWaveform appear in the public types of the waveform geometry.
    api(projects.core.djmodel)
    implementation(projects.core.dubmodel)
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
