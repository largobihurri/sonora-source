plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.sonora.core.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    // The converter worker's tests build its notifications, which read string resources.
    testOptions.unitTests.isIncludeAndroidResources = true
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
    // DUB mode: its ports and setup model, and the DJ mode flag it is exclusive with.
    implementation(projects.core.dubmodel)
    implementation(projects.core.djmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.datastore.preferences)
    // LRCLIB lyrics responses.
    implementation(libs.kotlinx.serialization.json)
    // Converter batches run as a foreground WorkManager job with a Hilt-injected worker.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    // "Compress" and archive import: stored ZIP entries, AES-256 when a password is set.
    implementation(libs.zip4j)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // TestListenableWorkerBuilder for the converter's worker.
    testImplementation(libs.androidx.work.testing)
    // Metadata round trips run real conversions through the transcoder's engine.
    testImplementation(projects.core.transcoder)
}
