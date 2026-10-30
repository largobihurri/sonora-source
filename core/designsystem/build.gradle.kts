plugins {
    alias(libs.plugins.sonora.android.library.compose)
}

android {
    namespace = "io.sonora.core.designsystem"

    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    implementation(projects.core.domain)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.coil.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kyant.backdrop)

    testImplementation(libs.kotlinx.coroutines.test)
    // The share sheet intent (ShareAudioTest) is built from real Intents.
    testImplementation(libs.robolectric)
}
