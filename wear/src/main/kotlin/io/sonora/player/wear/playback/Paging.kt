package io.sonora.player.wear.playback

/**
 * Page [page] (from 0) of [pageSize] items, as a browsing client asks for the children of a node. Media3 passes
 * `Int.MAX_VALUE` for "no paging", so the offset is computed in `Long`. A negative page or a page size below one is empty.
 */
fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= size) return emptyList()
    return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
}
