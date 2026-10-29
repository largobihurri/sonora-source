package io.sonora.core.watchsync.remote

/**
 * Whether a message really came from one of the user's watches: the sender must be a node that declares the Sonora watch
 * capability right now. (The Data Layer already only delivers between nodes with the same app and signature; this is the
 * second check.) A positive answer is remembered for [ttlMs] so a stream of volume steps costs one lookup, a node not found is
 * looked up again each time so a newly paired watch works at once, and a failed lookup trusts nobody.
 */
class TrustedNodes(
    private val lookup: suspend () -> Set<String>,
    private val now: () -> Long,
    private val ttlMs: Long = RemoteLimits.TRUST_TTL_MS,
) {
    private var known: Set<String> = emptySet()
    private var knownAt = 0L

    suspend fun isTrusted(nodeId: String): Boolean {
        if (nodeId.isEmpty()) return false
        if (nodeId in known && now() - knownAt < ttlMs) return true
        val fresh = try {
            lookup()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            known = emptySet()
            return false
        }
        known = fresh
        knownAt = now()
        return nodeId in fresh
    }
}
