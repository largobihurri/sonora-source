package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportRequestsTest {

    private val requests = ExportRequests()

    @Test
    fun `a request waits until it is consumed`() {
        assertThat(requests.pending.value).isNull()

        assertThat(requests.request("Album", listOf(A, B))).isTrue()

        val pending = requests.pending.value!!
        assertThat(pending).isEqualTo(ExportPlaylistRequest("Album", listOf(A, B)))
        assertThat(requests.consume(pending)).isTrue()
        assertThat(requests.pending.value).isNull()
        assertThat(requests.consume(pending)).isFalse()
    }

    @Test
    fun `a newer request replaces one not consumed yet, which can no longer be consumed`() {
        requests.request("Album", listOf(A))
        val older = requests.pending.value!!

        requests.request("Selection", listOf(B))

        assertThat(requests.pending.value).isEqualTo(ExportPlaylistRequest("Selection", listOf(B)))
        assertThat(requests.consume(older)).isFalse()
        assertThat(requests.pending.value).isEqualTo(ExportPlaylistRequest("Selection", listOf(B)))
    }

    @Test
    fun `a request without a name or without tracks is ignored`() {
        requests.request("Album", listOf(A))

        assertThat(requests.request("  ", listOf(B))).isFalse()
        assertThat(requests.request("Playlist", emptyList())).isFalse()

        assertThat(requests.pending.value).isEqualTo(ExportPlaylistRequest("Album", listOf(A)))
    }

    @Test
    fun `the name is trimmed and a track listed twice is kept once, in order`() {
        requests.request("  Album ", listOf(B, A, B))

        assertThat(requests.pending.value).isEqualTo(ExportPlaylistRequest("Album", listOf(B, A)))
    }

    private companion object {
        const val A = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.aiff"
        const val B = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fb.aiff"
    }
}
