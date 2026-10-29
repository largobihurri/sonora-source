package io.sonora.feature.settings.about

import io.sonora.core.designsystem.text.Strings
import io.sonora.feature.settings.R

/** Copy of Settings › About, built outside composables so JVM tests read the real strings. */
object AboutText {
    /** "Version 0.1.0 (12)": the installed version name and its build number (versionCode). */
    fun version(versionName: String?, versionCode: Long, strings: Strings): String =
        strings.get(R.string.about_version, versionName ?: "?", versionCode)

    /**
     * A licence or notice text as paragraphs that fit a phone: the blank lines separate them; inside one, the hard
     * line wraps and indentation of the plain-text original become single spaces, and lines that only underline a
     * heading (`=====`, `-----`) are dropped.
     */
    fun paragraphs(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) paragraphs += current.joinToString(" ")
            current.clear()
        }
        for (line in text.lines().map { it.trim() }) {
            when {
                line.isEmpty() -> flush()
                line.all { it == '=' || it == '-' } -> Unit
                else -> current += line.replace(WHITESPACE, " ")
            }
        }
        flush()
        return paragraphs
    }

    private val WHITESPACE = Regex("""\s+""")
}
