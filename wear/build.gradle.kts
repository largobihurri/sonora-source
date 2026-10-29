plugins {
    alias(libs.plugins.sonora.android.application)
}

android {
    namespace = "io.sonora.player.wear"

    defaultConfig {
        // The Wearable Data Layer pairs the watch app with the phone app by application id and signing key. Only the
        // phone's `full` build has the watch link (the Google Play build carries no Play services), so the watch app
        // shares the `full` build's id.
        applicationId = "io.sonora.player.full"
        // Wear OS 3 and later. The OnePlus Watch 3 runs Wear OS 5 (Android 14, API 34).
        minSdk = 30
        // Its own version, not the phone's `sonora.versionCode` (gradle.properties): the watch app is not on Google
        // Play, it is installed by hand next to the phone's `full` build.
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-like build for the watch: R8-optimised, not debuggable, signed with the common Bihurri key
        // (the convention plugin sets it) under the same application id, so `adb install -r` replaces the debug build
        // and keeps the watch's music (as the phone's).
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // The music transfer from the phone: the shared protocol and receiver, and the Data Layer channel it travels over.
    implementation(projects.core.watchsync)
    implementation(projects.core.watchlink)
    implementation(libs.play.services.wearable)

    // The watch's Kora node: capability contracts, caller policy and the Binder transport, so the other Bihurri apps on
    // the watch find the player as an on-device peer. Its contract and core types appear in this module's signatures.
    // Exact pin of a published release (gradle/libs.versions.toml); the phone-watch relay (`kora-wear`) is not used.
    implementation(libs.kora.android)

    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
