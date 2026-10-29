plugins {
    alias(libs.plugins.sonora.android.library)
}

android {
    namespace = "io.sonora.core.watchlink"
}

dependencies {
    api(projects.core.watchsync)
    // The Wearable Data Layer: capability lookup and the channel the songs travel over (the one dependency this feature adds).
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
}
