plugins {
    alias(libs.plugins.sonora.android.library.compose)
}

android {
    namespace = "io.sonora.core.cast"

    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.cast)
    // The route button takes its tint from Material 3's LocalContentColor.
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.robolectric)
}
