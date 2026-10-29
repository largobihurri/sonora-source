plugins {
    alias(libs.plugins.sonora.jvm.library)
}

dependencies {
    api(projects.core.domain)
}

tasks.test {
    // FsckFatIntegrationTest fails instead of skipping when FSCK_REQUIRED=1: rerun the tests when it changes.
    inputs.property("fsckRequired", providers.environmentVariable("FSCK_REQUIRED").orElse(""))
}
