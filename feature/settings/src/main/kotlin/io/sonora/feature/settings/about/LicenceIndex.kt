package io.sonora.feature.settings.about

/**
 * One bundled component on the Open-Source Licences page.
 *
 * [text] is the asset path, under `licences/`, of the full licence text (absent when [notice] already holds it, as
 * MIT and BSD notices do). [covers] lists what the entry accounts for: Maven coordinates (`group:artifact`), whole
 * groups (`group:*`), group prefixes (`androidx.*`), or a plain label for vendored code. [fullOnly] marks a component
 * that only the `full` build carries.
 */
data class LicenceNotice(
    val name: String,
    val licence: String,
    val url: String? = null,
    val text: String? = null,
    val covers: List<String> = emptyList(),
    val fullOnly: Boolean = false,
    val notice: String = "",
) {
    /** Whether this entry accounts for [coordinate] (`group:artifact`, or a vendored component's label). */
    fun covers(coordinate: String): Boolean {
        val group = coordinate.substringBefore(':')
        return covers.any { pattern ->
            when {
                pattern.endsWith(".*") -> pattern.removeSuffix(".*").let { group == it || group.startsWith("$it.") }
                pattern.endsWith(":*") -> group == pattern.removeSuffix(":*") && ':' in coordinate
                else -> coordinate == pattern
            }
        }
    }
}

/**
 * Reads the hand-maintained licence index ([ASSET]). The format, one block per component:
 *
 * ```
 * # A comment (any line starting with '#', inside a notice too).
 * [Component name]
 * licence: Apache-2.0
 * url: https://example.org          (optional)
 * text: texts/Apache-2.0.txt        (optional; under licences/)
 * covers: androidx.*, net.jthink:jaudiotagger
 * build: full                        (optional; "all" by default)
 *
 * The notice, verbatim, up to the next [block]; indentation is kept.
 * ```
 */
object LicenceIndex {
    const val ASSET = "licences/notices.txt"

    private val FIELDS = setOf("licence", "url", "text", "covers", "build")

    fun parse(text: String): List<LicenceNotice> {
        val notices = mutableListOf<LicenceNotice>()
        var block: Block? = null
        fun close() {
            block?.let { notices += it.toNotice() }
        }
        text.lines().forEachIndexed { index, line ->
            val number = index + 1
            when {
                line.startsWith("#") -> Unit
                line.startsWith("[") && line.trimEnd().endsWith("]") -> {
                    close()
                    val name = line.trim().removePrefix("[").removeSuffix("]").trim()
                    require(notices.none { it.name == name }) { "Line $number: [$name] is listed twice" }
                    block = Block(name, number)
                }
                else -> {
                    val current = block
                    require(current != null || line.isBlank()) { "Line $number: text before the first [component]" }
                    current?.add(line, number)
                }
            }
        }
        close()
        return notices
    }

    /** The entries the running build carries: the `full`-only ones only when [fullBuild]. */
    fun shownIn(notices: List<LicenceNotice>, fullBuild: Boolean): List<LicenceNotice> =
        notices.filter { fullBuild || !it.fullOnly }

    private class Block(val name: String, val line: Int) {
        val fields = LinkedHashMap<String, String>()
        val body = mutableListOf<String>()
        var inBody = false

        fun add(text: String, number: Int) {
            if (inBody) {
                body += text.trimEnd()
                return
            }
            if (text.isBlank()) {
                inBody = true
                return
            }
            val key = text.substringBefore(':').trim()
            require(':' in text && key in FIELDS) { "Line $number: unknown field \"$key\"" }
            val value = text.substringAfter(':').trim()
            if (key == "build") {
                require(value == "all" || value == "full") { "Line $number: build must be \"all\" or \"full\", not \"$value\"" }
            }
            fields[key] = value
        }

        fun toNotice(): LicenceNotice {
            val licence = fields["licence"]
            require(!licence.isNullOrEmpty()) { "Line $line: [$name] has no licence" }
            return LicenceNotice(
                name = name,
                licence = licence,
                url = fields["url"]?.ifEmpty { null },
                text = fields["text"]?.ifEmpty { null },
                covers = fields["covers"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                fullOnly = fields["build"] == "full",
                notice = body.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.joinToString("\n"),
            )
        }
    }
}
