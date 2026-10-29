package io.sonora.player.ui

/** Follows a finger sliding across the tab bar: reports a tab only when the strip reaches a different one. */
class TabScrub(startIndex: Int) {
    private var current = startIndex

    /** The tab now nearest the strip position; returns it when it changed, `null` otherwise (or for a miss, -1). */
    fun moveTo(index: Int): Int? {
        if (index < 0 || index == current) return null
        current = index
        return index
    }
}
