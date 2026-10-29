plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.transcoder"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.audiodsp)
    implementation(projects.core.mp3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
    // Reads converted files back the way taggers and players do (round-trip tests only).
    testImplementation(libs.jaudiotagger)

    // Reads the converted files back the way the batch runner verifies them (round-trip tests only).
    androidTestImplementation(projects.core.decoder)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
