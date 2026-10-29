plugins {
    alias(libs.plugins.sonora.android.library.compose)
}

android {
    namespace = "io.sonora.core.designsystem"
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
}
