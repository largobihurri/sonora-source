plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.djanalysis"

    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    // The DJ ports (DjTrackAnalyzer, DjAnalysisStore, AudioIdIndex, BeatActivationModelFactory) and the engine.
    api(projects.core.djmodel)
    implementation(projects.core.djengine)
    // The DUB mode flag the DJ layout is exclusive with.
    implementation(projects.core.dubmodel)
    // PcmDecoder, PlaybackController and AnalysisException.
    implementation(projects.core.domain)
    // The qm-dsp AnalysisEngine, the fallback beat grid.
    implementation(projects.core.analysis)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // SyntheticAudio, the deterministic test signals of the DJ engine.
    testImplementation(testFixtures(projects.core.djengine))
}
