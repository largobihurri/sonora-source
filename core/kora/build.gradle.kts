plugins {
    alias(libs.plugins.sonora.android.library)
    alias(libs.plugins.sonora.hilt)
}

android {
    namespace = "io.sonora.core.kora"
}

dependencies {
    implementation(projects.core.domain)
    // The Kora node: capability contracts, caller policy, audit, and the Binder transport with the adb gate.
    // Its contract and core types appear in this module's public signatures, so they are `api` in Kora itself.
    implementation(libs.kora.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
