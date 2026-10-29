package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioIdTest {

    private val hash = "0123456789abcdef".repeat(4)

    @Test
    fun `the schemes are the versioned tags`() {
        assertThat(AudioId.PAYLOAD_V1).isEqualTo("ap1")
        assertThat(AudioId.WHOLE_FILE_V1).isEqualTo("wf1")
    }

    @Test
    fun `an id is the scheme and the hash joined by a colon`() {
        val id = AudioId.of(AudioId.PAYLOAD_V1, hash)

        assertThat(id.value).isEqualTo("ap1:$hash")
        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(hash)
        assertThat(id.toString()).isEqualTo("ap1:$hash")
        assertThat(AudioId.of(AudioId.WHOLE_FILE_V1, hash).value).isEqualTo("wf1:$hash")
    }

    @Test
    fun `the same scheme and hash give equal ids`() {
        assertThat(AudioId.of("ap1", hash)).isEqualTo(AudioId.of("ap1", hash))
        assertThat(AudioId.of("ap1", hash)).isNotEqualTo(AudioId.of("wf1", hash))
    }

    @Test
    fun `the hash must be 64 lower-case hex digits`() {
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap1", hash.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap1", hash + "0") }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap1", hash.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap1", "g".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap1", "") }
    }

    @Test
    fun `the scheme must be a lower-case tag`() {
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("", hash) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("AP1", hash) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("ap:1", hash) }
        assertThrows(IllegalArgumentException::class.java) { AudioId.of("1ap", hash) }
        // A later scheme still parses, so a store written by a newer app can be read.
        assertThat(AudioId.of("ap2", hash).scheme).isEqualTo("ap2")
    }

    @Test
    fun `parse reads back what value wrote and rejects anything else`() {
        val id = AudioId.of("wf1", hash)

        assertThat(AudioId.parse(id.value)).isEqualTo(id)
        assertThat(AudioId.parse("ap1$hash")).isNull()
        assertThat(AudioId.parse("ap1:")).isNull()
        assertThat(AudioId.parse(":$hash")).isNull()
        assertThat(AudioId.parse("ap1:xyz")).isNull()
        assertThat(AudioId.parse("")).isNull()
    }
}
