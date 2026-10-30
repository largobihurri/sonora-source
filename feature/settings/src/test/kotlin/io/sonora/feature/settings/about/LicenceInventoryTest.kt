package io.sonora.feature.settings.about

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Keeps the hand-maintained notices (`src/main/assets/licences/notices.txt`) in step with the build: every library of
 * `gradle/libs.versions.toml` that a shipped module (the app, the watch app, `core/`, `feature/`, and the convention
 * plugins that add libraries to them) puts on its runtime classpath must be covered by a notice, or be listed in
 * [NOT_BUNDLED] with the reason. Transitive libraries are not read from the build: their groups are listed by hand.
 */
class LicenceInventoryTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val notices = LicenceIndex.parse(File(root, "feature/settings/src/main/assets/${LicenceIndex.ASSET}").readText())

    /** Catalogue aliases a shipped module may use that are not in the app, with why. Keep it short. */
    private val NOT_BUNDLED = emptyMap<String, String>()

    /** alias (as written in the catalogue) -> `group:artifact`. */
    private fun catalogue(): Map<String, String> {
        val section = File(root, "gradle/libs.versions.toml").readLines()
            .dropWhile { it.trim() != "[libraries]" }.drop(1)
            .takeWhile { !it.trim().startsWith("[") }
        return section.mapNotNull { LIBRARY.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Accessors (`androidx.core.ktx`) of the libraries shipped modules add to a runtime configuration. */
    private fun shippedAccessors(): Set<String> {
        val buildFiles = listOf(File(root, "app"), File(root, "wear")).map { File(it, "build.gradle.kts") } +
            listOf("core", "feature").flatMap { group ->
                File(root, group).listFiles().orEmpty().map { File(it, "build.gradle.kts") }
            }
        val fromBuilds = buildFiles.filter { it.isFile }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") }.flatMap { line ->
                BUILD_USE.findAll(line).map { it.groupValues[2] }
            }
        }
        val conventions = File(root, "build-logic/convention/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        val fromConventions = conventions.flatMap { file ->
            CONVENTION_USE.findAll(file.readText()).map { it.groupValues[2].replace('-', '.') }
        }
        return (fromBuilds + fromConventions).toSet()
    }

    @Test
    fun `every library a shipped module bundles has a notice`() {
        val catalogue = catalogue()
        val byAccessor = catalogue.mapKeys { (alias, _) -> alias.replace('-', '.').replace('_', '.') }
        val shipped = shippedAccessors()
        // Guards the scan itself: these are bundled for certain, so finding none means the patterns broke.
        assertThat(shipped).containsAtLeast("kora.android", "jaudiotagger", "androidx.core.ktx", "hilt.android")

        val unknown = shipped.filterNot { it in byAccessor }
        assertWithMessage("libs.* accessors used by a build file but missing from libs.versions.toml").that(unknown).isEmpty()

        val uncovered = shipped.filterNot { it.replace('.', '-') in NOT_BUNDLED }
            .map { byAccessor.getValue(it) }
            .filter { coordinate -> notices.none { it.covers(coordinate) } }
            .sorted()
        assertWithMessage("bundled libraries without an entry in ${LicenceIndex.ASSET} (add one, or explain in NOT_BUNDLED)")
            .that(uncovered).isEmpty()
        assertWithMessage("NOT_BUNDLED names aliases the catalogue no longer has").that(NOT_BUNDLED.keys - catalogue.keys).isEmpty()
    }

    @Test
    fun `Google Play services are listed as part of the full build only`() {
        val playServices = notices.filter { it.covers("com.google.android.gms:play-services-wearable") }

        assertThat(playServices).isNotEmpty()
        assertThat(playServices.all { it.fullOnly }).isTrue()
    }

    @Test
    fun `every full licence text a notice points to is a packaged asset`() {
        val assetFolders = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "odd", ".claude") }
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("src/main/assets") }
            .toList()
        val missing = notices.mapNotNull { it.text }.distinct()
            .filter { text -> assetFolders.none { File(it, "licences/$text").isFile } }

        assertThat(notices.count { it.text != null }).isAtLeast(5)
        assertWithMessage("licence texts named in ${LicenceIndex.ASSET} but not found under any src/main/assets/licences")
            .that(missing).isEmpty()
    }

    @Test
    fun `Sonora itself is listed under the GPL with its source code link`() {
        val sonora = notices.single { it.name == "Sonora" }

        assertThat(sonora.licence).isEqualTo("GPL-3.0-or-later")
        assertThat(sonora.url).isEqualTo(AboutLinks.SOURCE_CODE)
        assertThat(sonora.text).isEqualTo("texts/GPL-3.0.txt")
        assertThat(sonora.fullOnly).isFalse()
    }

    @Test
    fun `the vendored LAME encoder is listed under its own LGPL text`() {
        val lame = notices.single { it.name == "LAME" }
        val vendored = File(root, "core/mp3/src/main/cpp/lame/COPYING").readText()
        val shipped = File(root, "feature/settings/src/main/assets/licences/${lame.text}").readText()

        assertThat(lame.licence).isEqualTo("LGPL-2.0-or-later")
        assertThat(lame.text).isEqualTo("texts/LGPL-2.0.txt")
        assertThat(shipped).isEqualTo(vendored)
        assertThat(lame.fullOnly).isFalse()
    }

    @Test
    fun `Kora is listed under the Apache License 2_0`() {
        val kora = notices.single { it.name == "Kora" }

        assertThat(kora.licence).isEqualTo("Apache-2.0")
        assertThat(kora.text).isEqualTo("texts/Apache-2.0.txt")
        assertThat(kora.url).isEqualTo("https://github.com/largobihurri/kora-public")
        assertThat(kora.fullOnly).isFalse()
    }

    @Test
    fun `jaudiotagger is listed under LGPL 2_1 or later`() {
        val jaudiotagger = notices.single { it.name == "jaudiotagger" }

        assertThat(jaudiotagger.licence).isEqualTo("LGPL-2.1-or-later")
        assertThat(jaudiotagger.text).isEqualTo("texts/LGPL-2.1.txt")
    }

    private companion object {
        /** `alias = { module = "group:artifact", ... }` in the `[libraries]` table. */
        val LIBRARY = Regex("""^\s*([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""")

        /** `implementation(libs.x)`, `api(...)`, `"fullImplementation"(...)`, also around `platform(...)`. */
        val BUILD_USE = Regex("""(?<![\w"])"?(implementation|api|fullImplementation|playImplementation)"?\(\s*(?:platform\()?libs\.([\w.]+)""")

        /** `add("implementation", libs.lib("alias"))` in a convention plugin. */
        val CONVENTION_USE = Regex("""add\("(implementation|api)",\s*(?:platform\()?libs\.lib\("([\w-]+)"\)""")
    }
}
