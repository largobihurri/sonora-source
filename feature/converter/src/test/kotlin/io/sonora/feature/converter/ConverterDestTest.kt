package io.sonora.feature.converter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConverterDestTest {

    @Test
    fun `a stack survives encoding`() {
        val stack = listOf(ConverterDest.Main, ConverterDest.History, ConverterDest.Batch(42), ConverterDest.LibraryPicker)

        assertThat(decodeConverterStack(encodeConverterStack(stack))).isEqualTo(stack)
    }

    @Test
    fun `the format screens and a folder's songs survive encoding`() {
        val folder = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FDJ"
        val song = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FDJ/document/primary%3AMusic%2FDJ%2Fa.flac"
        val stack = listOf(
            ConverterDest.Main,
            ConverterDest.Output(null, null),
            ConverterDest.Folder(folder),
            ConverterDest.Output(folder, null),
            ConverterDest.Output(folder, song),
        )

        assertThat(decodeConverterStack(encodeConverterStack(stack))).isEqualTo(stack)
    }

    @Test
    fun `unreadable entries are dropped and the stack always starts at the main screen`() {
        assertThat(decodeConverterStack(listOf("history", "batch:x", "unknown")))
            .containsExactly(ConverterDest.Main, ConverterDest.History).inOrder()
        assertThat(decodeConverterStack(emptyList())).containsExactly(ConverterDest.Main)
    }

    @Test
    fun `a batch opened from its notification shows on the converter itself`() {
        val stack = mutableListOf(ConverterDest.Main, ConverterDest.History, ConverterDest.Batch(7))
        val shown = mutableListOf<Long>()

        assertThat(openBatchFromNotification(42, stack, shown::add)).isTrue()

        assertThat(shown).containsExactly(42L)
        assertThat(stack).containsExactly(ConverterDest.Main)
    }

    @Test
    fun `no batch to open leaves everything as it is`() {
        val stack = mutableListOf(ConverterDest.Main, ConverterDest.History)
        val shown = mutableListOf<Long>()

        assertThat(openBatchFromNotification(null, stack, shown::add)).isFalse()

        assertThat(shown).isEmpty()
        assertThat(stack).containsExactly(ConverterDest.Main, ConverterDest.History).inOrder()
    }

    @Test
    fun `songs sent from the Library show on the converter's main screen`() {
        val stack = mutableListOf(ConverterDest.Main, ConverterDest.History, ConverterDest.LibraryPicker)

        assertThat(showSourcesFromLibrary(true, stack)).isTrue()
        assertThat(stack).containsExactly(ConverterDest.Main)

        assertThat(showSourcesFromLibrary(false, stack)).isFalse()
    }
}
