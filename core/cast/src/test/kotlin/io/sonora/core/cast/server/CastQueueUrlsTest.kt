package io.sonora.core.cast.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastQueueUrlsTest {

    private val token = CastToken("0123456789abcdef0123456789abcdef")
    private var now = 0L
    private val urls = CastQueueUrls("192.168.1.20", 41_234, token, now = { now })

    private fun song(n: Int) = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F$n.flac"

    @Test
    fun `a queue entry gets an audio and an artwork URL under the session token`() {
        val mapped = urls.map("1:1", song(1), "audio/flac", "file:///data/art/1.jpg")

        assertThat(mapped.audio).isEqualTo("http://192.168.1.20:41234/0123456789abcdef0123456789abcdef/audio/1")
        assertThat(mapped.artwork).isEqualTo("http://192.168.1.20:41234/0123456789abcdef0123456789abcdef/art/1")
    }

    @Test
    fun `an entry without artwork gets no artwork URL`() {
        assertThat(urls.map("1:1", song(1), "audio/flac", null).artwork).isNull()
    }

    @Test
    fun `the same entry keeps its URLs and distinct entries get distinct ones`() {
        val first = urls.map("1:1", song(1), "audio/flac", null)
        val second = urls.map("1:2", song(1), "audio/flac", null)

        assertThat(urls.map("1:1", song(1), "audio/flac", null)).isEqualTo(first)
        assertThat(second.audio).isNotEqualTo(first.audio)
    }

    @Test
    fun `a path resolves to the file it was mapped from`() {
        urls.map("1:1", song(1), "audio/flac", "file:///data/art/1.jpg")

        assertThat(urls.resolve("/$TOKEN/audio/1")).isEqualTo(Served.Audio(song(1), "audio/flac"))
        assertThat(urls.resolve("/$TOKEN/art/1")).isEqualTo(Served.Artwork("file:///data/art/1.jpg"))
        assertThat(urls.resolve("/$TOKEN/audio/1?t=5")).isEqualTo(Served.Audio(song(1), "audio/flac"))
    }

    @Test
    fun `a path with a wrong or missing token resolves to nothing`() {
        urls.map("1:1", song(1), "audio/flac", null)

        assertThat(urls.resolve("/0123456789abcdef0123456789abcdee/audio/1")).isNull()
        assertThat(urls.resolve("/audio/1")).isNull()
        assertThat(urls.resolve("/")).isNull()
        assertThat(urls.resolve("")).isNull()
    }

    @Test
    fun `unknown ids, kinds and malformed paths resolve to nothing`() {
        urls.map("1:1", song(1), "audio/flac", null)

        assertThat(urls.resolve("/$TOKEN/audio/2")).isNull()
        assertThat(urls.resolve("/$TOKEN/art/1")).isNull() // no artwork for this entry
        assertThat(urls.resolve("/$TOKEN/video/1")).isNull()
        assertThat(urls.resolve("/$TOKEN/audio/x")).isNull()
        assertThat(urls.resolve("/$TOKEN/audio/1/extra")).isNull()
        assertThat(urls.resolve("/$TOKEN/../audio/1")).isNull()
    }

    @Test
    fun `an audio URL leads back to the file it stands for`() {
        val mapped = urls.map("1:1", song(1), "audio/flac", null)

        assertThat(urls.originalAudioUri(mapped.audio)).isEqualTo(song(1))
        assertThat(urls.originalAudioUri("http://192.168.1.20:41234/$TOKEN/audio/9")).isNull()
        assertThat(urls.originalAudioUri("http://elsewhere/$TOKEN/audio/1")).isNull()
    }

    @Test
    fun `entries that left the queue stop being served`() {
        urls.map("1:1", song(1), "audio/flac", null)
        urls.map("1:2", song(2), "audio/flac", null)
        now += CastQueueUrls.GRACE_MS

        urls.retain(setOf("1:2"))

        assertThat(urls.resolve("/$TOKEN/audio/1")).isNull()
        assertThat(urls.resolve("/$TOKEN/audio/2")).isEqualTo(Served.Audio(song(2), "audio/flac"))
    }

    @Test
    fun `a just-mapped entry survives a queue the receiver has not reported yet`() {
        urls.map("1:1", song(1), "audio/flac", null)
        now += CastQueueUrls.GRACE_MS
        urls.map("1:2", song(2), "audio/flac", null)

        // An older queue status, from before the second entry reached the receiver.
        urls.retain(setOf("1:1"))

        assertThat(urls.resolve("/$TOKEN/audio/2")).isNotNull()
    }

    @Test
    fun `an empty queue report keeps every entry`() {
        urls.map("1:1", song(1), "audio/flac", null)
        now += CastQueueUrls.GRACE_MS

        // A receiver clears its queue for a moment while it loads a new one.
        urls.retain(emptySet())

        assertThat(urls.resolve("/$TOKEN/audio/1")).isNotNull()
    }

    @Test
    fun `a removed entry mapped again gets a fresh id`() {
        val first = urls.map("1:1", song(1), "audio/flac", null)
        now += CastQueueUrls.GRACE_MS
        urls.retain(setOf("other"))

        val again = urls.map("1:1", song(1), "audio/flac", null)

        assertThat(again.audio).isNotEqualTo(first.audio)
        assertThat(urls.resolve("/$TOKEN/audio/1")).isNull()
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
