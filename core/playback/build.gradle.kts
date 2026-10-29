plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.playback"

    // Robolectric tests read the module's strings (media button labels).
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    implementation(projects.core.domain)
    // The external control API: parser, dispatcher, queries and the caller policy behind ExternalApiProvider.
    implementation(projects.core.api)
    // The DJ loops: the quantizer and the model, and the sample-accurate engine the audio sink hosts.
    implementation(projects.core.djmodel)
    implementation(projects.core.djloop)
    // The DUB stage: the mode flag, the setup and the stage port, and the real-time DSP its audio sink hosts.
    implementation(projects.core.dubmodel)
    implementation(projects.core.audiodsp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
