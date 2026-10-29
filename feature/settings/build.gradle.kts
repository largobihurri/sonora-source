plugins {
    alias(libs.plugins.sonora.android.library.compose)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.feature.settings"
}

dependencies {
    implementation(projects.core.domain)
    // The DJ-mode switch and the analysis status of the playing track (D2).
    implementation(projects.core.djmodel)
    // Settings › DUB Mode: the mode flag and the stored effect setup.
    implementation(projects.core.dubmodel)
    implementation(projects.core.designsystem)
    // The Watch page: what is on the watch, sending progress, removal (W4).
    implementation(projects.core.watchport)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
