package io.sonora.player.wear

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch app's English strings against their Castellano and Euskara translations: the same keys and the same
 * placeholders, none empty. (The phone's `TranslationConsistencyTest` also reads these files, but only runs with `:app`.)
 */
class WearTranslationTest {

    private val resources = File("src/main/res").absoluteFile

    private fun read(qualifier: String): Map<String, String> {
        val file = File(resources, "values$qualifier/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return buildMap {
            val nodes = document.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as org.w3c.dom.Element
                if (element.getAttribute("translatable") != "false") put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    private fun check(qualifier: String) {
        val english = read("")
        val translation = read(qualifier)
        assertEquals("Untranslated in values$qualifier", emptyList<String>(), (english.keys - translation.keys).sorted())
        assertEquals("Not in values/ but in values$qualifier", emptyList<String>(), (translation.keys - english.keys).sorted())
        val mismatched = translation.filter { (key, text) -> placeholders(english.getValue(key)) != placeholders(text) }.keys
        assertEquals("Placeholders differ in values$qualifier", emptySet<String>(), mismatched)
        assertEquals("Empty in values$qualifier", emptySet<String>(), translation.filterValues { it.isBlank() }.keys)
    }

    @Test
    fun `every English string is translated into Castellano with the same placeholders`() = check("-es")

    @Test
    fun `every English string is translated into Euskara with the same placeholders`() = check("-eu")

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
    }
}
