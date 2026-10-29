package io.sonora.feature.settings.about

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.settings.XmlStrings
import org.junit.Test

class AboutTextTest {

    @Test
    fun `the version line names the version and its build number in each language`() {
        assertThat(AboutText.version("0.1.0-full", 12L, XmlStrings.of())).isEqualTo("Version 0.1.0-full (12)")
        assertThat(AboutText.version("0.1.0", 1L, XmlStrings.of("-es"))).isEqualTo("Versión 0.1.0 (1)")
        assertThat(AboutText.version("0.1.0", 1L, XmlStrings.of("-eu"))).isEqualTo("0.1.0 bertsioa (1)")
    }

    @Test
    fun `a build without a version name still shows its build number`() {
        assertThat(AboutText.version(null, 3L, XmlStrings.of())).isEqualTo("Version ? (3)")
    }

    @Test
    fun `a licence text reflows into paragraphs without its hard wraps, indentation or underline rules`() {
        val text = "Mozilla Public License\n=====\n\n  1. Definitions\n  --------------\n\n    1.1. \"Contributor\" means each\n    individual or legal entity.\n\n\n  * Item\n"

        assertThat(AboutText.paragraphs(text))
            .containsExactly("Mozilla Public License", "1. Definitions", "1.1. \"Contributor\" means each individual or legal entity.", "* Item")
            .inOrder()
        assertThat(AboutText.paragraphs("\n \n")).isEmpty()
    }

    @Test
    fun `the links point at the public source repository and its privacy policy`() {
        assertThat(AboutLinks.SOURCE_CODE).isEqualTo("https://github.com/largobihurri/sonora-source")
        assertThat(AboutLinks.PRIVACY_POLICY).isEqualTo("https://github.com/largobihurri/sonora-source/blob/main/PRIVACY.md")
    }
}
