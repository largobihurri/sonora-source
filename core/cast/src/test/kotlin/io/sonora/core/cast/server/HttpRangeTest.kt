package io.sonora.core.cast.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpRangeTest {

    @Test
    fun `no header asks for the whole file`() {
        assertThat(HttpRange.parse(null, 1_000)).isEqualTo(RangeRequest.Whole)
        assertThat(HttpRange.parse("  ", 1_000)).isEqualTo(RangeRequest.Whole)
    }

    @Test
    fun `a closed range is served as asked`() {
        assertThat(HttpRange.parse("bytes=100-199", 1_000)).isEqualTo(RangeRequest.Part(100, 199))
    }

    @Test
    fun `an open range runs to the end of the file`() {
        assertThat(HttpRange.parse("bytes=900-", 1_000)).isEqualTo(RangeRequest.Part(900, 999))
        // What a Cast receiver sends first: the whole file as a range, so seeking is known to work.
        assertThat(HttpRange.parse("bytes=0-", 1_000)).isEqualTo(RangeRequest.Part(0, 999))
    }

    @Test
    fun `a range past the end is cut at the last byte`() {
        assertThat(HttpRange.parse("bytes=500-5000", 1_000)).isEqualTo(RangeRequest.Part(500, 999))
    }

    @Test
    fun `a suffix range takes the last bytes`() {
        assertThat(HttpRange.parse("bytes=-100", 1_000)).isEqualTo(RangeRequest.Part(900, 999))
        assertThat(HttpRange.parse("bytes=-5000", 1_000)).isEqualTo(RangeRequest.Part(0, 999))
    }

    @Test
    fun `a range starting at or past the end cannot be satisfied`() {
        assertThat(HttpRange.parse("bytes=1000-", 1_000)).isEqualTo(RangeRequest.Unsatisfiable)
        assertThat(HttpRange.parse("bytes=2000-3000", 1_000)).isEqualTo(RangeRequest.Unsatisfiable)
        assertThat(HttpRange.parse("bytes=-0", 1_000)).isEqualTo(RangeRequest.Unsatisfiable)
        assertThat(HttpRange.parse("bytes=0-", 0)).isEqualTo(RangeRequest.Unsatisfiable)
    }

    @Test
    fun `units, spacing and case are read leniently`() {
        assertThat(HttpRange.parse("Bytes = 10 - 19", 1_000)).isEqualTo(RangeRequest.Part(10, 19))
    }

    @Test
    fun `malformed, reversed or multiple ranges fall back to the whole file`() {
        for (header in listOf("bytes=abc-", "bytes=5-2", "bytes=-", "items=0-10", "bytes=0-1,5-9", "bytes=--3", "bytes=1-2-3")) {
            assertThat(HttpRange.parse(header, 1_000)).isEqualTo(RangeRequest.Whole)
        }
    }

    @Test
    fun `an unknown length ignores the range`() {
        assertThat(HttpRange.parse("bytes=100-199", -1)).isEqualTo(RangeRequest.Whole)
    }

    @Test
    fun `a part knows its length and its Content-Range`() {
        val part = RangeRequest.Part(100, 199)
        assertThat(part.length).isEqualTo(100)
        assertThat(part.contentRange(1_000)).isEqualTo("bytes 100-199/1000")
    }
}
