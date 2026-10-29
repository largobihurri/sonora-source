plugins {
    alias(libs.plugins.sonora.android.library.compose)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.feature.nowplaying"
}

dependencies {
    implementation(projects.core.domain)
    // The DJ-mode switch and the analysis status of the playing track (D2).
    implementation(projects.core.djmodel)
    implementation(projects.core.designsystem)
    // The equalizer panel opens from the bottom row.
    implementation(projects.feature.equalizer)
    // The DJ screen (waveform, tempo controls) replaces the artwork view while DJ mode is on.
    implementation(projects.feature.dj)
    // The DUB screen (pads, XY filter, preamp) is Now Playing's layout while DUB mode is on, exclusive with DJ's.
    implementation(projects.feature.dub)
    implementation(projects.core.dubmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.palette.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
}
