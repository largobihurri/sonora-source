package io.sonora.core.designsystem.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareAudioTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val song = Uri.parse("content://media/song/1")
    private val other = Uri.parse("content://media/song/2")

    /** The chooser [shareAudio] started, and the send intent inside it. */
    private fun started(): Pair<Intent, Intent> {
        val chooser = shadowOf(app).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        return chooser to chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    }

    @Test
    fun `one song is sent with its type, its title and a read grant, under the chooser title`() {
        assertThat(app.shareAudio(listOf(song), listOf("audio/flac"), "Waterloo", "Share Song")).isTrue()

        val (chooser, send) = started()
        assertThat(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString()).isEqualTo("Share Song")
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(song)
        assertThat(send.type).isEqualTo("audio/flac")
        assertThat(send.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("Waterloo")
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(song)
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `several songs of one type are sent together with that type`() {
        assertThat(app.shareAudio(listOf(song, other), listOf("audio/mpeg", "audio/mpeg"), null, "Share")).isTrue()

        val (_, send) = started()
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)).containsExactly(song, other).inOrder()
        assertThat(send.type).isEqualTo("audio/mpeg")
        assertThat(send.hasExtra(Intent.EXTRA_TITLE)).isFalse()
        assertThat(send.clipData!!.itemCount).isEqualTo(2)
        assertThat(send.clipData!!.getItemAt(1).uri).isEqualTo(other)
    }

    @Test
    fun `songs of mixed types share as any audio`() {
        app.shareAudio(listOf(song, other), listOf("audio/mpeg", "audio/flac"), null, "Share")

        assertThat(started().second.type).isEqualTo("audio/*")
    }

    @Test
    fun `with no app to take the songs it reports false`() {
        shadowOf(app).checkActivities(true)

        assertThat(app.shareAudio(listOf(song), listOf("audio/flac"), "Waterloo", "Share Song")).isFalse()
    }

    @Test
    fun `nothing to share opens nothing`() {
        assertThat(app.shareAudio(emptyList(), emptyList(), null, "Share")).isTrue()

        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }
}
