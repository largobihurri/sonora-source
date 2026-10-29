package io.sonora.core.domain.car

import java.text.Normalizer

/**
 * The letters a long tab is split into (Android Auto has no paging): a name's first letter with accents folded
 * (`É` goes under `E`), and [OTHER] for names starting with anything else; letters in alphabetical order, [OTHER] last.
 */
object CarBuckets {
    const val OTHER = "#"

    fun keyOf(name: String): String {
        val first = Normalizer.normalize(name.trimStart().take(2), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar()
        return if (first != null && first in 'A'..'Z') first.toString() else OTHER
    }

    fun isKey(key: String): Boolean = key == OTHER || (key.length == 1 && key[0] in 'A'..'Z')

    /** The keys present in [names], in bucket order. */
    fun keys(names: List<String>): List<String> =
        names.mapTo(HashSet(), ::keyOf).sortedWith(compareBy<String> { it == OTHER }.thenBy { it })
}
