package io.sonora.core.watchsync

/** A device the Data Layer reports as reachable with the Sonora watch capability. */
data class WatchNode(val id: String, val name: String, val isNearby: Boolean)

/** Chooses which reachable node is "the watch". */
object WatchNodes {
    /** A nearby node first (connected directly), then by name, so the same set always gives the same choice. */
    fun pick(nodes: List<WatchNode>): WatchNode? =
        nodes.sortedWith(compareByDescending<WatchNode> { it.isNearby }.thenBy { it.name }.thenBy { it.id }).firstOrNull()
}
