package io.sonora.core.watchsync.remote

/** A state to send to one watch. */
data class RemotePush(val nodeId: String, val state: RemoteState)

/**
 * The phone's table of watches that asked for pushes. Each has a lease ([leaseMs]) the watch renews with a new subscribe, so
 * a watch that went away stops costing messages by itself. Not thread safe: use it from one thread.
 */
class RemoteSubscriptions(private val leaseMs: Long = RemoteLimits.LEASE_MS) {
    private class Lease(var withQueue: Boolean, var expiresAt: Long) {
        var lastPushed: RemoteState? = null
        var lastPushedAt = 0L
    }

    private val leases = LinkedHashMap<String, Lease>()

    /** Adds or renews [nodeId]'s lease. The next [due] sends it the state, whether or not it changed. */
    fun subscribe(nodeId: String, withQueue: Boolean, now: Long) {
        val lease = leases.getOrPut(nodeId) { Lease(withQueue, now + leaseMs) }
        lease.withQueue = withQueue
        lease.expiresAt = now + leaseMs
        lease.lastPushed = null
    }

    fun unsubscribe(nodeId: String) {
        leases.remove(nodeId)
    }

    /** The push to [nodeId] failed (the watch is out of reach): the next [due] sends the state again. */
    fun markUnsent(nodeId: String) {
        leases[nodeId]?.lastPushed = null
    }

    fun hasSubscribers(now: Long): Boolean {
        prune(now)
        return leases.isNotEmpty()
    }

    /**
     * The pushes owed at [now]: each live lease's state, from [stateFor] (given whether the queue is wanted), when it needs
     * pushing (see [StatePushPolicy]). What is returned counts as pushed.
     */
    fun due(now: Long, stateFor: (withQueue: Boolean) -> RemoteState): List<RemotePush> {
        prune(now)
        if (leases.isEmpty()) return emptyList()
        val states = HashMap<Boolean, RemoteState>(2)
        val pushes = ArrayList<RemotePush>(leases.size)
        for ((nodeId, lease) in leases) {
            val state = states.getOrPut(lease.withQueue) { stateFor(lease.withQueue) }
            if (StatePushPolicy.needsPush(lease.lastPushed, lease.lastPushedAt, state, now)) {
                lease.lastPushed = state
                lease.lastPushedAt = now
                pushes += RemotePush(nodeId, state)
            }
        }
        return pushes
    }

    private fun prune(now: Long) {
        leases.values.removeAll { it.expiresAt < now }
    }
}
