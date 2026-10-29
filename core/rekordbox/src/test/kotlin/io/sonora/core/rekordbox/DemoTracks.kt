package io.sonora.core.rekordbox

/** The rekordcrate demo export checked into the test resources (rekordbox's own files, used for parity tests). */
object DemoTracks {
    fun resource(name: String): ByteArray =
        checkNotNull(DemoTracks::class.java.getResourceAsStream("/rekordcrate/demo_tracks/$name")) {
            "missing test resource $name"
        }.use { it.readBytes() }
}
