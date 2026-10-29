plugins {
    alias(libs.plugins.sonora.android.library.compose)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.feature.cdjexport"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions.unitTests.all {
        // StickTreeOracleTest writes a complete exported stick here, to check it with rekordcrate.
        val stickOut = layout.buildDirectory.dir("stick-out")
        it.outputs.dir(stickOut)
        it.systemProperty("sonora.stickOutDir", stickOut.get().asFile.path)
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.rekordbox)
    // The DJ analysis, the user's grid edit and the cues the export writes into the analysis files.
    implementation(projects.core.djmodel)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    // The independent ANLZ reader, to check what the export writes.
    testImplementation(testFixtures(projects.core.rekordbox))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
