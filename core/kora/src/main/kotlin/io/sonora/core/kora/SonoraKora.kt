package io.sonora.core.kora

import android.app.Application
import android.util.Log
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.DefaultCallerPolicy
import io.sonora.core.domain.port.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sonora as a Kora node: provides `media.library`, `media.playback`, `media.history` and `media.artwork` (cover art) to the Bihurri apps signed with the same key,
 * and, while the user's "External control" switch is on, to the adb shell (the Kora gate `${applicationId}.kora`, MCP
 * included).
 *
 * [start] runs once, at the end of `Application.onCreate`. It builds nothing heavy: the handlers take their ports
 * lazily, so the library and the player are only created by the first call that needs them.
 */
@Singleton
class SonoraKora @Inject internal constructor(
    private val library: MediaLibraryHandlers,
    private val playback: MediaPlaybackHandlers,
    private val history: MediaHistoryHandlers,
    private val artwork: MediaArtworkHandlers,
    private val preferences: dagger.Lazy<PreferencesRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(application: Application) {
        val access = SonoraExternalAccess(
            switchOn = { preferences.get().preferences.first().externalControlEnabled },
            onRefused = DeniedRequests(::logRefusal)::onDenied,
        )
        KoraAndroid.install(
            application,
            policy = DefaultCallerPolicy(access),
            audit = AuditLog { Log.d(TAG, it.toString()) },
        ) {
            provide(MediaLibrary) { library.register(this) }
            provide(MediaPlayback) { playback.register(this) }
            provide(MediaHistory) { history.register(this) }
            provide(MediaArtwork) { artwork.register(this) }
        }
        // The policy is asked when a subscription opens, so ones already running end when the user turns the switch off.
        scope.launch {
            preferences.get().preferences.map { it.externalControlEnabled }.turnedOff().collect {
                KoraAndroid.revokeUntrustedSubscriptions()
            }
        }
    }

    private fun logRefusal(caller: Caller, scope: Scope) {
        Log.i(TAG, "refused a ${caller.kind} caller the scope $scope (External control off or scope not granted)")
        // Which app asked stays out of release logs (R8 strips Log.d there, app/proguard-rules.pro).
        Log.d(TAG, "refused caller: ${caller.id}")
    }

    private companion object {
        const val TAG = "SonoraKora"
    }
}
