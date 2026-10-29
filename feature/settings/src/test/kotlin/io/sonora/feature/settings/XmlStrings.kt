package io.sonora.feature.settings

import io.sonora.core.designsystem.text.Strings
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [Strings] read straight from resource XML files, as Android resolves them for one language: each source pairs a
 * module's `R` class with its `res` folder; [qualifier] picks `values` (`""`) or a translation such as `"-eu"`.
 * Lets pure text builders be tested with the real copy on the JVM.
 */
class XmlStrings(
    sources: List<Pair<Class<*>, File>>,
    qualifier: String = "",
    override val locale: Locale = Locale.forLanguageTag(qualifier.removePrefix("-").ifEmpty { "en" }),
) : Strings {
    private val names = HashMap<Int, String>()
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        for ((rClass, res) in sources) {
            for (type in rClass.declaredClasses.filter { it.simpleName == "string" || it.simpleName == "plurals" }) {
                for (field in type.fields) check(names.put(field.getInt(null), field.name) == null) { "Duplicate id of ${field.name}" }
            }
            // The default values first, so a translation falls back to them as on Android.
            for (folder in listOf("values", "values$qualifier").distinct()) {
                File(res, folder).listFiles { file -> file.extension == "xml" }.orEmpty().forEach(::read)
            }
        }
    }

    override fun get(id: Int, vararg args: Any): String = format(strings.getValue(name(id)), args)

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val items = plurals.getValue(name(id))
        return format(items[if (count == 1) "one" else "other"] ?: items.getValue("other"), args)
    }

    private fun name(id: Int) = names[id] ?: error("No resource name for id $id")

    private fun format(text: String, args: Array<out Any>) = if (args.isEmpty()) text else String.format(locale, text, *args)

    private fun read(file: File) {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        for (element in root.childElements()) {
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> strings[name] = unescape(element.textContent)
                "plurals" -> plurals[name] = element.childElements().associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** Android's string escapes: quotes, apostrophes, `\n`, `\t`, `\@`, `\?`, `\uXXXX`. */
    private fun unescape(raw: String): String {
        val text = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> {
                        out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        /** This module's copy plus the design system's. */
        fun of(qualifier: String = "") = XmlStrings(
            listOf(
                R::class.java to File("src/main/res"),
                io.sonora.core.designsystem.R::class.java to File("../../core/designsystem/src/main/res"),
            ),
            qualifier,
        )
    }
}
