plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.watchphone"
}

dependencies {
    // The controller port and the sender's types it exposes.
    api(projects.core.watchport)
    implementation(projects.core.watchlink)
    implementation(projects.core.domain)
    // The Wearable Data Layer: the listener service the watch's remote messages wake.
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
