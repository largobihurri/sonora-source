plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.djstore"

    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

ksp {
    // Every schema version is committed: the migration tests (v2 onwards) open the older files.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // DjAnalysisStore, AudioIdIndex and the types they carry are this module's public contract.
    api(projects.core.djmodel)
    // The SDJ1 codec of the grid and waveform BLOBs.
    implementation(projects.core.djengine)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
