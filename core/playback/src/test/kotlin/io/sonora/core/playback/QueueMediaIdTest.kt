package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueMediaIdTest {

    @Test
    fun `round trips the track and queue item ids`() {
        val parsed = QueueMediaId.parse(QueueMediaId.format(trackId = 42, itemId = 7))

        assertThat(parsed).isEqualTo(QueueMediaId.Parsed(trackId = 42, itemId = 7))
    }

    @Test
    fun `media ids without an item id keep the track id`() {
        assertThat(QueueMediaId.parse("42")).isEqualTo(QueueMediaId.Parsed(trackId = 42, itemId = null))
        assertThat(QueueMediaId.parse("x:y")).isEqualTo(QueueMediaId.Parsed(trackId = null, itemId = null))
        assertThat(QueueMediaId.parse("")).isEqualTo(QueueMediaId.Parsed(trackId = null, itemId = null))
    }
}
