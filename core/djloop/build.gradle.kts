plugins {
    alias(libs.plugins.sonora.jvm.library)
}

// The pure part of the sample-accurate DJ loops: PCM blending, the output-to-source timeline and the loop engine.
// No Android: the Media3 sink that hosts the engine lives in :core:playback.
dependencies {
    // LoopFrames is the loop the engine plays and the quantizer produces.
    api(projects.core.djmodel)
}
