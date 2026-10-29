package io.sonora.core.watchsync

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `hex of the empty input is the known digest`() {
        assertThat(Sha256.hex(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `hex of abc is the known digest`() {
        assertThat(Sha256.hex("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `a file hashes like its bytes`() {
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        val file = File(folder.root, "a.bin").apply { writeBytes(bytes) }

        assertThat(Sha256.hexOfFile(file)).isEqualTo(Sha256.hex(bytes))
    }

    @Test
    fun `a valid digest is 64 hex characters in either case`() {
        assertThat(Sha256.isValidHex("a".repeat(64))).isTrue()
        assertThat(Sha256.isValidHex("A".repeat(64))).isTrue()
        assertThat(Sha256.isValidHex("a".repeat(63))).isFalse()
        assertThat(Sha256.isValidHex("g".repeat(64))).isFalse()
    }
}
