plugins {
    alias(libs.plugins.sonora.android.library.compose)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.feature.dub"
}

dependencies {
    // The DUB stage port, the stored setup and its model.
    api(projects.core.dubmodel)
    // The playback state (playing or not) the meters follow.
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    testImplementation(libs.kotlinx.coroutines.test)
}
