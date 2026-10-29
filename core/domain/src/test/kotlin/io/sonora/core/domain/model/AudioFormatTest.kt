package io.sonora.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioFormatTest {

    @Test
    fun `every format resolves from its own extension`() {
        AudioFormat.entries.forEach { format ->
            assertThat(AudioFormat.fromExtension(format.fileExtension)).isEqualTo(format)
        }
    }

    @Test
    fun `extension lookup ignores case, dot and aliases`() {
        assertThat(AudioFormat.fromExtension(".FLAC")).isEqualTo(AudioFormat.FLAC)
        assertThat(AudioFormat.fromExtension("aif")).isEqualTo(AudioFormat.AIFF)
        assertThat(AudioFormat.fromExtension("aac")).isEqualTo(AudioFormat.AAC_M4A)
    }

    @Test
    fun `unknown extension returns null`() {
        assertThat(AudioFormat.fromExtension("xyz")).isNull()
    }
}
