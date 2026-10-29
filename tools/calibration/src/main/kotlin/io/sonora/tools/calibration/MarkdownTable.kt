package io.sonora.tools.calibration

import java.util.Locale

/** A small GitHub-flavoured Markdown table renderer for the calibration reports. */
object MarkdownTable {

    /** Column headers that go with [summaryRow]. */
    val SUMMARY_HEADERS: List<String> = listOf("", "n", "mean", "median", "p95", "min", "max")

    /**
     * Renders [headers] and [rows] as a table with padded columns, ending in a newline. Columns in [rightAligned]
     * (by index) are right-aligned, the others left-aligned. Every row must have one cell per header; `|` in a cell
     * is escaped and line breaks become spaces.
     */
    fun render(headers: List<String>, rows: List<List<String>>, rightAligned: Set<Int> = emptySet()): String {
        require(headers.isNotEmpty()) { "A table needs at least one column" }
        for (row in rows) require(row.size == headers.size) { "Row has ${row.size} cells, expected ${headers.size}: $row" }

        val head = headers.map(::escape)
        val body = rows.map { row -> row.map(::escape) }
        val widths = IntArray(headers.size) { c -> maxOf(MIN_WIDTH, head[c].length, body.maxOfOrNull { it[c].length } ?: 0) }

        fun line(cells: List<String>) = cells.indices.joinToString(" | ", "| ", " |") { c ->
            if (c in rightAligned) cells[c].padStart(widths[c]) else cells[c].padEnd(widths[c])
        }

        return buildString {
            appendLine(line(head))
            appendLine(
                widths.indices.joinToString(" | ", "| ", " |") { c ->
                    if (c in rightAligned) "-".repeat(widths[c] - 1) + ":" else ":" + "-".repeat(widths[c] - 1)
                },
            )
            for (row in body) appendLine(line(row))
        }
    }

    /** [value] with [decimals] decimals and a dot whatever the default locale; `NaN` is `n/a`. */
    fun number(value: Double, decimals: Int): String {
        if (value.isNaN()) return "n/a"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val text = String.format(Locale.ROOT, "%.${decimals}f", value)
        // A tiny negative value rounds to "-0.0": show it without the sign.
        return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) text.substring(1) else text
    }

    /** A row for [SUMMARY_HEADERS]: [label], the count and the statistics of [summary]. */
    fun summaryRow(label: String, summary: Summary, decimals: Int): List<String> = listOf(
        label,
        summary.count.toString(),
        number(summary.mean, decimals),
        number(summary.median, decimals),
        number(summary.p95, decimals),
        number(summary.min, decimals),
        number(summary.max, decimals),
    )

    private fun escape(cell: String): String = cell.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    private const val MIN_WIDTH = 2
}
