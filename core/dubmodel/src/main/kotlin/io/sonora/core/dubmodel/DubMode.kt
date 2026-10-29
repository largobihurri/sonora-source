package io.sonora.core.dubmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether DUB mode is on: Now Playing shows the DUB layout and the player hosts the DUB stage. Off until the user turns
 * it on (Now Playing's "…" menu and Settings both flip it). The DUB layout and the DJ layout are exclusive: turning one
 * on turns the other off, so at most one of the two flags is ever on.
 */
interface DubModeSettings {
    val enabled: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)
}

/**
 * The stored DUB effect setup ([DubSetup]): what the DUB screen edits and the DUB stage plays with. Performance state
 * (held or latched pads, the filter position, the preamp kills) is never stored here.
 */
interface DubSetupRepository {

    /** The current setup (stored, or a [preview] in progress), defaults for anything never set; re-emits on every change. */
    val setup: Flow<DubSetup>

    /** Atomically replaces the setup with [transform] applied to the current one, clamped, and stores the result. */
    suspend fun update(transform: (DubSetup) -> DubSetup)

    /**
     * Shows [setup] (clamped) to every reader at once without storing it: a change still in progress (a slider or a
     * corner being dragged), heard live but written only by the [update] that ends it.
     */
    fun preview(setup: DubSetup)
}
