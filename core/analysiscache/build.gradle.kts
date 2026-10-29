plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.analysiscache"

    testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(projects.core.domain)
    // Only for AndroidTrackAnalyzer.ANALYZER_VERSION, the version the cache is keyed by (see AnalysisCacheModule).
    implementation(projects.core.analysis)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
