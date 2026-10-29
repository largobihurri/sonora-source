package io.sonora.player.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every module's English copy (`values/`) against its Castellano (`values-es/`) and Euskara (`values-eu/`)
 * translations, read from disk: the same keys, the same placeholders, and tab labels short enough for the tab bar.
 * Resources merge into one namespace at build time, so a translation may live in a module other than its base.
 */
class TranslationConsistencyTest {

    /** A string resource or one quantity of a plural. */
    private data class Entry(val key: String, val text: String)

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun resourceFolders(qualifier: String): List<File> = root.walkTopDown()
        .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".git", "odd") && !dir.name.startsWith("sonora-rv") }
        .filter { it.isDirectory && it.name == "values$qualifier" && it.parentFile.name == "res" }
        .filter { "/src/main/" in it.invariantSeparatorsPath }
        .toList()
        .also { check(it.isNotEmpty()) { "No values$qualifier resource folders found under $root" } }

    /** key -> text for strings; `key#quantity` -> text for plural items. Untranslatable strings are left out. */
    private fun read(qualifier: String): Map<String, String> = buildMap {
        for (folder in resourceFolders(qualifier)) {
            for (file in folder.listFiles { f -> f.extension == "xml" }.orEmpty()) {
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                for (element in document.children()) {
                    val name = element.getAttribute("name")
                    when (element.tagName) {
                        "string" -> if (element.getAttribute("translatable") != "false") put(name, element.textContent)
                        "plurals" -> element.children().forEach { put("$name#${it.getAttribute("quantity")}", it.textContent) }
                    }
                }
            }
        }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** The format placeholders of [text] as positional `index$type` (so `%d` and `%1$d` match); `%%` is not one. */
    private fun placeholders(text: String): List<String> {
        var next = 1
        return PLACEHOLDER.findAll(text).mapNotNull { match ->
            val (position, conversion) = match.destructured
            if (conversion == "%") return@mapNotNull null
            val index = position.removeSuffix("$").toIntOrNull() ?: next++
            "$index\$$conversion"
        }.sorted().toList()
    }

    private val english = read("")

    /** The plural names of the base copy, with the base text of their `other` quantity. */
    private val englishPlurals = english.filterKeys { it.endsWith("#other") }.mapKeys { it.key.substringBefore('#') }

    private fun check(qualifier: String) {
        val translation = read(qualifier)
        val baseStrings = english.filterKeys { '#' !in it }
        val missing = baseStrings.keys.filterNot(translation::containsKey) +
            englishPlurals.keys.filterNot { name -> translation.keys.any { it.startsWith("$name#") } }
        assertEquals("Untranslated in values$qualifier", emptyList<String>(), missing.sorted())

        val orphans = translation.keys.filterNot { key ->
            if ('#' in key) key.substringBefore('#') in englishPlurals else key in baseStrings
        }
        assertEquals("Not in values/ but in values$qualifier", emptyList<String>(), orphans.sorted())

        val mismatched = translation.mapNotNull { (key, text) ->
            val base = if ('#' in key) englishPlurals.getValue(key.substringBefore('#')) else baseStrings.getValue(key)
            "$key: ${placeholders(base)} vs ${placeholders(text)}".takeIf { placeholders(base) != placeholders(text) }
        }
        assertEquals("Placeholders differ in values$qualifier", emptyList<String>(), mismatched)

        val emptyText = translation.filterValues { it.isBlank() }.keys
        assertEquals("Empty in values$qualifier", emptySet<String>(), emptyText)
    }

    @Test
    fun `every English string and plural is translated into Castellano with the same placeholders`() = check("-es")

    @Test
    fun `every English string and plural is translated into Euskara with the same placeholders`() = check("-eu")

    @Test
    fun `plurals offer every quantity the language needs`() {
        for ((qualifier, quantities) in mapOf("-es" to setOf("one", "many", "other"), "-eu" to setOf("one", "other"))) {
            val translation = read(qualifier)
            for (name in englishPlurals.keys) {
                val present = translation.keys.filter { it.startsWith("$name#") }.map { it.substringAfter('#') }.toSet()
                assertEquals("$name in values$qualifier", quantities, present)
            }
        }
    }

    @Test
    fun `tab labels stay one short word in every language`() {
        for (qualifier in listOf("", "-es", "-eu")) {
            val tabs = read(qualifier).filterKeys { it in TAB_LABELS }
            assertEquals("tab labels in values$qualifier", TAB_LABELS, tabs.keys)
            for ((key, label) in tabs) {
                assertTrue("$key in values$qualifier is \"$label\"", ' ' !in label.trim() && label.trim().length <= MAX_TAB_LABEL)
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
        val TAB_LABELS = setOf("tab_library", "tab_search", "tab_converter")

        /** Longest label the 88 dp tab slot shows without shrinking (see TabBarGeometry), in characters. */
        const val MAX_TAB_LABEL = 12
    }
}
