package io.sonora.core.data.library.tags

import com.google.common.truth.Truth.assertThat
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.tag.TagException
import org.junit.Test

class ReadFailuresTest {

    @Test
    fun `access and I-O failures are transient, so the file is read again by the next scan`() {
        assertThat(ReadFailures.isTransient(SecurityException("grant revoked"))).isTrue()
        assertThat(ReadFailures.isTransient(FileNotFoundException("provider gone"))).isTrue()
        assertThat(ReadFailures.isTransient(IOException("I/O error"))).isTrue()
    }

    @Test
    fun `damaged or unsupported content is permanent, so the file is indexed untagged`() {
        assertThat(ReadFailures.isTransient(CannotReadException("no audio header"))).isFalse()
        assertThat(ReadFailures.isTransient(InvalidAudioFrameException("bad frame"))).isFalse()
        assertThat(ReadFailures.isTransient(TagException("garbled tag"))).isFalse()
        assertThat(ReadFailures.isTransient(EOFException("truncated"))).isFalse()
        assertThat(ReadFailures.isTransient(IndexOutOfBoundsException())).isFalse()
    }
}
