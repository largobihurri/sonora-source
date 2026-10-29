package io.sonora.core.domain.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogRedactionTest {

    private val songUri =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSecret%20Song.flac"

    @Test
    fun `a content uri keeps only its scheme and authority plus a short hash`() {
        val redacted = redactForLog(songUri)

        assertThat(redacted).matches("content://com\\.android\\.externalstorage\\.documents/…#[0-9a-f]{8}")
        assertThat(redacted).doesNotContain("Music")
        assertThat(redacted).doesNotContain("Secret")
    }

    @Test
    fun `a file uri drops the whole path`() {
        val redacted = redactForLog("file:///storage/emulated/0/Music/Artist/Album/01 Track.mp3")

        assertThat(redacted).matches("file:///…#[0-9a-f]{8}")
        assertThat(redacted).doesNotContain("storage")
    }

    @Test
    fun `a query or fragment after the authority is redacted too`() {
        assertThat(redactForLog("content://media?title=Private")).matches("content://media/…#[0-9a-f]{8}")
        assertThat(redactForLog("content://media#Private")).matches("content://media/…#[0-9a-f]{8}")
    }

    @Test
    fun `a uri with nothing after its authority is kept as it is`() {
        assertThat(redactForLog("content://io.sonora.player.kora")).isEqualTo("content://io.sonora.player.kora")
    }

    @Test
    fun `a plain path or file name becomes only a short hash`() {
        assertThat(redactForLog("/storage/emulated/0/Music/Secret")).matches("…#[0-9a-f]{8}")
        assertThat(redactForLog("Secret Song.flac")).matches("…#[0-9a-f]{8}")
    }

    @Test
    fun `the same value always gives the same hash and different values give different ones`() {
        assertThat(redactForLog(songUri)).isEqualTo(redactForLog(songUri))
        assertThat(redactForLog("$songUri.2")).isNotEqualTo(redactForLog(songUri))
        assertThat(redactForLog("a.flac")).isNotEqualTo(redactForLog("b.flac"))
    }

    @Test
    fun `any object is redacted through its string form`() {
        val uriLike = object {
            override fun toString() = songUri
        }

        assertThat(redactForLog(uriLike)).isEqualTo(redactForLog(songUri))
    }

    @Test
    fun `an empty value and null have nothing to hide`() {
        assertThat(redactForLog("")).isEmpty()
        assertThat(redactForLog(null)).isEqualTo("null")
    }
}
