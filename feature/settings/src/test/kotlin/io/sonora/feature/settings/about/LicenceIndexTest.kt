package io.sonora.feature.settings.about

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LicenceIndexTest {

    private val index = """
        # Hand-maintained; comments are skipped.
        [AndroidX]
        licence: Apache-2.0
        url: https://developer.android.com/jetpack/androidx
        text: texts/Apache-2.0.txt
        covers: androidx.*, com.google.dagger:*

        Copyright The Android Open Source Project.

        [kissfft]
        licence: BSD-3-Clause
        covers: kissfft

        Copyright (c) 2003-2010 Mark Borgerding
        # a comment inside the notice is skipped too

            * Indented lines keep their indentation.


        [Google Play services]
        licence: Android Software Development Kit License
        covers: com.google.android.gms:*
        build: full
    """.trimIndent()

    @Test
    fun `a block gives its name, fields and notice`() {
        val notices = LicenceIndex.parse(index)

        assertThat(notices.map { it.name }).containsExactly("AndroidX", "kissfft", "Google Play services").inOrder()
        val androidx = notices[0]
        assertThat(androidx.licence).isEqualTo("Apache-2.0")
        assertThat(androidx.url).isEqualTo("https://developer.android.com/jetpack/androidx")
        assertThat(androidx.text).isEqualTo("texts/Apache-2.0.txt")
        assertThat(androidx.covers).containsExactly("androidx.*", "com.google.dagger:*").inOrder()
        assertThat(androidx.fullOnly).isFalse()
        assertThat(androidx.notice).isEqualTo("Copyright The Android Open Source Project.")
    }

    @Test
    fun `the notice keeps its inner lines and indentation and drops comments and trailing blank lines`() {
        val kissfft = LicenceIndex.parse(index)[1]

        assertThat(kissfft.url).isNull()
        assertThat(kissfft.text).isNull()
        assertThat(kissfft.notice).isEqualTo("Copyright (c) 2003-2010 Mark Borgerding\n\n    * Indented lines keep their indentation.")
    }

    @Test
    fun `build full marks a component only the full build carries, and a block may have no notice`() {
        val play = LicenceIndex.parse(index)[2]

        assertThat(play.fullOnly).isTrue()
        assertThat(play.notice).isEmpty()
        assertThat(LicenceIndex.shownIn(LicenceIndex.parse(index), fullBuild = false).map { it.name })
            .containsExactly("AndroidX", "kissfft").inOrder()
        assertThat(LicenceIndex.shownIn(LicenceIndex.parse(index), fullBuild = true)).hasSize(3)
    }

    @Test
    fun `covers matches a group prefix, a whole group or one artifact`() {
        val androidx = LicenceIndex.parse(index)[0]
        val kissfft = LicenceIndex.parse(index)[1]

        assertThat(androidx.covers("androidx.compose.ui:ui")).isTrue()
        assertThat(androidx.covers("androidx:anything")).isTrue()
        assertThat(androidx.covers("androidxtra.lib:lib")).isFalse()
        assertThat(androidx.covers("com.google.dagger:hilt-android")).isTrue()
        assertThat(androidx.covers("com.google.dagger.extra:lib")).isFalse()
        assertThat(kissfft.covers("kissfft")).isTrue()
        assertThat(kissfft.covers("kissfft:other")).isFalse()
    }

    @Test
    fun `a malformed index fails with the line that is wrong`() {
        val missingLicence = assertThrows(IllegalArgumentException::class.java) {
            LicenceIndex.parse("[Thing]\nurl: https://example.org\n")
        }
        assertThat(missingLicence).hasMessageThat().isEqualTo("Line 1: [Thing] has no licence")

        val unknownKey = assertThrows(IllegalArgumentException::class.java) {
            LicenceIndex.parse("[Thing]\nlicence: MIT\nauthor: me\n")
        }
        assertThat(unknownKey).hasMessageThat().isEqualTo("Line 3: unknown field \"author\"")

        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            LicenceIndex.parse("[Thing]\nlicence: MIT\n\n[Thing]\nlicence: MIT\n")
        }
        assertThat(duplicate).hasMessageThat().isEqualTo("Line 4: [Thing] is listed twice")

        val badBuild = assertThrows(IllegalArgumentException::class.java) {
            LicenceIndex.parse("[Thing]\nlicence: MIT\nbuild: play\n")
        }
        assertThat(badBuild).hasMessageThat().isEqualTo("Line 3: build must be \"all\" or \"full\", not \"play\"")

        val outside = assertThrows(IllegalArgumentException::class.java) {
            LicenceIndex.parse("licence: MIT\n")
        }
        assertThat(outside).hasMessageThat().isEqualTo("Line 1: text before the first [component]")
    }
}
