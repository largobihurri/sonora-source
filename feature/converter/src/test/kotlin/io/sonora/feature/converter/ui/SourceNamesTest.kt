package io.sonora.feature.converter.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceNamesTest {

    @Test
    fun `a picked folder is named by its provider when it can`() {
        assertThat(SourceNames.treeName({ "primary:Music/DJ" }, { "DJ Sets" }, "tree")).isEqualTo("DJ Sets")
        assertThat(SourceNames.treeName({ "primary:Music/DJ" }, { null }, "tree")).isEqualTo("DJ")
        assertThat(SourceNames.treeName({ "primary:" }, { null }, "tree")).isEqualTo("primary")
    }

    @Test
    fun `a picker result that is no tree falls back to its last path segment instead of crashing`() {
        val name = SourceNames.treeName({ throw IllegalArgumentException("Invalid URI: content://x/document/1") }, { null }, "Music")

        assertThat(name).isEqualTo("Music")
    }
}
