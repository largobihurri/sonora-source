plugins {
    alias(libs.plugins.sonora.android.application)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.player"

    defaultConfig {
        applicationId = "io.sonora.player"
        // One version for both flavors, from gradle.properties (`sonora.versionCode`, `sonora.versionName`; CLAUDE.md
        // "Build and test"). Google Play needs a higher versionCode for every upload, so it only ever goes up.
        versionCode = providers.gradleProperty("sonora.versionCode").get().let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
                ?: throw GradleException("sonora.versionCode must be a whole number from 1 to 2100000000 (Google Play's limit), not '$raw'")
        }
        versionName = providers.gradleProperty("sonora.versionName").get()
        // The native code ships for these ABIs only: qm-dsp (core:analysis) is built for them, and ONNX Runtime
        // (core:beatmodel) would otherwise add its armeabi-v7a and x86 libraries for devices without the analyser.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // The app switches language itself (Castellano by default, whatever the device language), so an app bundle
    // must ship every translation instead of only the device's languages.
    bundle {
        language {
            enableSplit = false
        }
    }

    // BuildConfig.APPLICATION_ID / FLAVOR: the distribution tests check each build's identity.
    buildFeatures {
        buildConfig = true
    }

    // Two distributions of the app, installable side by side (odd/tasks/play-release.md S6, S9):
    // - `play`, sold on Google Play under the application id Play knows: no proprietary Google Play services (no
    //   Chromecast, no Wear OS watch link), because the app is GPL-3.0 and carries third-party GPL code (qm-dsp, the
    //   Mixxx-derived analysis).
    // - `full`, for personal installs: Cast and the watch link included. Its own id (`.full`), name ("Sonora Full",
    //   `src/full/res`), so it keeps its own library and settings next to the Play build; move
    //   them across with Settings > Backup. Distributing it has the same GPL problem.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("full") {
            dimension = "distribution"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        // The build uploaded to Google Play: R8 shrinks, optimises and obfuscates the code (keep rules in
        // proguard-rules.pro and the modules' consumer rules) and unused resources are removed.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-like build for measuring on a real phone: the release build (R8, resource shrinking), not debuggable
        // (so ART compiles it fully and the baseline profiles apply), under the same application id and signed with the
        // same key as every other build type (the common Bihurri key from the convention plugin), so `adb install -r`
        // replaces the debug build.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            matchingFallbacks += "release"
        }
        // The benchmark build under its own application id and name ("Sonora Nightly", `src/nightly/res`), so a
        // trial build installs next to the everyday one instead of replacing it. It keeps its own library and settings.
        create("nightly") {
            initWith(getByName("benchmark"))
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
            matchingFallbacks += listOf("benchmark", "release")
        }
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.playback)
    // Chromecast (B12), `full` only: media3-cast brings Google Play services.
    "fullImplementation"(projects.core.cast)
    // The session router the `full` build binds hands Media3 players between Cast and playback.
    "fullImplementation"(libs.androidx.media3.session)
    // Sonora as a Kora node: media.library and media.playback for peers, the adb shell and MCP.
    implementation(projects.core.kora)
    implementation(projects.core.transcoder)
    // The watch port every build binds (the `play` build to UnavailableWatchSync).
    implementation(projects.core.watchport)
    // Sending albums and playlists to the Wear OS watch (the controller, the foreground service, the Hilt bindings),
    // `full` only: the Wearable Data Layer is Google Play services.
    "fullImplementation"(projects.core.watchphone)
    implementation(projects.core.usbstorage)
    implementation(projects.core.decoder)
    implementation(projects.core.analysis)
    implementation(projects.core.analysiscache)
    // DJ mode: the analysis wiring, its permanent store and the Beat This! model (ONNX Runtime) behind their Hilt modules.
    implementation(projects.core.djanalysis)
    implementation(projects.core.djstore)
    implementation(projects.core.beatmodel)
    // DUB mode: its ports and setup model (the storage and its Hilt bindings are in :core:data).
    implementation(projects.core.dubmodel)
    implementation(projects.feature.library)
    implementation(projects.feature.nowplaying)
    implementation(projects.feature.equalizer)
    implementation(projects.feature.dj)
    implementation(projects.feature.converter)
    implementation(projects.feature.settings)
    implementation(projects.feature.cdjexport)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // The application hands WorkManager the Hilt worker factory (on-demand initialization).
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
}
