package io.sonora.core.kora

import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.ExternalAccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Who outside the Bihurri family may use Sonora's Kora node. Signed peers and Sonora itself never reach this: the
 * default policy trusts them.
 *
 * - The master switch is the user's "External control" setting ([switchOn]), the same one the old external API used.
 *   It is read per call, so turning it off takes effect on the next call.
 * - Grants are parity with the old API: while the switch is on, the adb shell and root (`SHELL`) may use every scope
 *   Sonora provides; any other app (`EXTERNAL_APP`) or remote agent gets nothing, because there is no screen yet where
 *   the user could approve one app at a time (the old API's per-app Android permission has no Kora counterpart).
 * - A refusal is handed to [onRefused] once per caller and scope (see [DeniedRequests]).
 */
internal class SonoraExternalAccess(
    private val switchOn: suspend () -> Boolean,
    private val onRefused: (Caller, Scope) -> Unit,
) : ExternalAccess {
    override suspend fun isEnabled(): Boolean = switchOn()

    override suspend fun grantedScopes(caller: Caller): Set<Scope> =
        if (caller.kind == CallerKind.SHELL) SHELL_SCOPES else emptySet()

    override suspend fun onDenied(caller: Caller, scope: Scope) = onRefused(caller, scope)

    companion object {
        /** Every scope of the capabilities Sonora provides: derived from the contract, so a new member is covered. */
        val SHELL_SCOPES: Set<Scope> =
            listOf(MediaLibrary, MediaPlayback, MediaHistory, MediaArtwork).flatMap { capability ->
                capability.operations.map { it.scope } + capability.events.map { it.scope }
            }.toSet()
    }
}

/**
 * Passes each (caller, scope) refusal on once. A caller can repeat a refused request as often as it likes, and
 * `onDenied` runs on the call path, so the repeats cost one set lookup. The set is bounded: past [MAX_ENTRIES]
 * distinct pairs, new ones are dropped rather than growing without limit.
 */
internal class DeniedRequests(private val record: (Caller, Scope) -> Unit) {
    private val seen = ConcurrentHashMap.newKeySet<Pair<Caller, Scope>>()

    fun onDenied(caller: Caller, scope: Scope) {
        if (seen.size < MAX_ENTRIES && seen.add(caller to scope)) record(caller, scope)
    }

    companion object {
        const val MAX_ENTRIES = 256
    }
}

/** Emits each time the switch goes from on to off (the starting value and repeats are not changes). */
internal fun Flow<Boolean>.turnedOff(): Flow<Unit> {
    val source = this
    return flow {
        var previous: Boolean? = null
        source.collect { enabled ->
            if (previous == true && !enabled) emit(Unit)
            previous = enabled
        }
    }
}
