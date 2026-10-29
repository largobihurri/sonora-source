plugins {
    alias(libs.plugins.sonora.jvm.library)
}

// What the phone's UI uses to put music on the watch, without the Wearable Data Layer: the `full` build binds it to
// :core:watchphone, the `play` build (no Google Play services) to [io.sonora.core.watchport.UnavailableWatchSync].
dependencies {
    api(projects.core.domain)
    // The sender's types (SendProgress, SendReport, WatchState...) are part of this port's API for the Watch screen.
    api(projects.core.watchsync)
}
