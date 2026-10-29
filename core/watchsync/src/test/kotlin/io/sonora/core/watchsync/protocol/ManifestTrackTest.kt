package io.sonora.core.watchsync.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import org.junit.Test

class ManifestTrackTest {

    private val valid = ManifestTrack(
        id = "0123456789abcdef",
        title = "Song",
        artist = null,
        albumArtist = null,
        album = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 1000,
        sizeBytes = 10,
        sha256 = "b".repeat(64),
    )

    @Test
    fun `a well formed entry has no problem`() {
        assertThat(valid.problem()).isNull()
    }

    @Test
    fun `a bad id is a problem`() {
        assertThat(valid.copy(id = "../x").problem()).isNotNull()
    }

    @Test
    fun `a size of zero or less is a problem`() {
        assertThat(valid.copy(sizeBytes = 0).problem()).isNotNull()
        assertThat(valid.copy(sizeBytes = -5).problem()).isNotNull()
    }

    @Test
    fun `a bad checksum is a problem`() {
        assertThat(valid.copy(sha256 = "xyz").problem()).isNotNull()
    }

    @Test
    fun `a negative duration is a problem`() {
        assertThat(valid.copy(durationMs = -1).problem()).isNotNull()
    }

    @Test
    fun `absurdly long text is a problem`() {
        assertThat(valid.copy(title = "x".repeat(5000)).problem()).isNotNull()
    }
}
