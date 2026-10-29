plugins {
    alias(libs.plugins.sonora.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.djmodel)
    api(projects.core.audiodsp)

    // SyntheticAudio builds signals for the keys of the DJ model.
    testFixturesApi(projects.core.djmodel)

    testImplementation(libs.kotlinx.coroutines.test)
}
