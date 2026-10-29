plugins {
    alias(libs.plugins.sonora.jvm.library)
}

dependencies {
    // The mode flag is a StateFlow and the setup a Flow.
    api(libs.kotlinx.coroutines.core)
}
