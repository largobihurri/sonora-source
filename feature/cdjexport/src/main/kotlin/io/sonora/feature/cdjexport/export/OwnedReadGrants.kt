package io.sonora.feature.cdjexport.export

/** The app's persisted read grants as the system keeps them. Blocking. */
interface SystemReadGrants {
    /** Every URI the app holds a persisted read grant for. */
    fun held(): Set<String>

    /** Persists read access to [uri]; `false` when the provider does not offer it. */
    fun take(uri: String): Boolean

    /** Gives up the persisted read grant of [uri], if there is one. */
    fun release(uri: String)
}

/** Where [OwnedReadGrants] keeps its record between runs of the app. Blocking. */
interface GrantStore {
    fun load(): Set<String>

    fun save(uris: Set<String>)
}

/**
 * The persisted read grants the export took itself, so it only ever gives up those: a grant the app already held for
 * something else (a library folder, say) stays that feature's.
 *
 * Bounded so the export cannot use up Android's per-app cap on persisted grants (128 before Android 11, 512 since;
 * past it the system drops the app's oldest grants, the library's included):
 * - the record survives the process ([store]), so grants an earlier run kept and could not give up (the app was
 *   killed first) are given up by the next run ([releaseLeftovers]) unless it takes them again;
 * - at most [maxOwned] are held at once; past that [take] says no and the pick relies on the picker's own grant.
 *
 * Thread-safe; blocking.
 */
class OwnedReadGrants(
    private val store: GrantStore,
    private val system: SystemReadGrants,
    private val maxOwned: Int = MAX_OWNED,
) {
    private val owned: MutableSet<String> = store.load().toMutableSet()

    /** What an earlier run left: given up by [releaseLeftovers] unless this run takes it again. */
    private val leftovers: MutableSet<String> = owned.toMutableSet()

    /** Keeps read access to [uri]; `false` when the provider does not offer it or [maxOwned] are already held. */
    @Synchronized
    fun take(uri: String): Boolean {
        leftovers -= uri
        if (uri in owned) return system.take(uri)
        if (uri in system.held()) return system.take(uri)
        if (owned.size >= maxOwned || !system.take(uri)) return false
        owned += uri
        store.save(owned.toSet())
        return true
    }

    /** Gives up [uri] if the export took it; anything else is left alone. */
    @Synchronized
    fun release(uri: String) {
        if (!owned.remove(uri)) return
        leftovers -= uri
        system.release(uri)
        store.save(owned.toSet())
    }

    /** Gives up what an earlier run of the app kept and this run has not taken again. Once per run is enough. */
    @Synchronized
    fun releaseLeftovers() {
        if (leftovers.isEmpty()) return
        leftovers.forEach { uri ->
            owned -= uri
            system.release(uri)
        }
        leftovers.clear()
        store.save(owned.toSet())
    }

    companion object {
        /** Far below the system's cap, so the export always leaves room for the app's other grants. */
        const val MAX_OWNED = 64
    }
}
