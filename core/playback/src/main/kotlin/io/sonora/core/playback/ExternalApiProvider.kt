package io.sonora.core.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.sonora.core.api.ApiDispatcher
import io.sonora.core.api.ApiPorts
import io.sonora.core.api.ApiQueryEngine
import io.sonora.core.api.CallerPolicy
import io.sonora.core.api.CallerVerdict
import io.sonora.core.api.EXTERNAL_CONTROL_PERMISSION
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.playback.api.ApiBundles
import io.sonora.core.playback.api.ApiCursors
import io.sonora.core.playback.api.ExternalApiGateway
import io.sonora.core.playback.api.GatewayLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll

/**
 * The external control API (`content://<applicationId>.api`): `query()` reads the player and the library as cursors
 * and `call()` runs commands and answers with a `Bundle`. The parsing, the commands and the queries are the pure
 * `core:api`; this is the Android adapter (see `docs/external-api.md` for the contract).
 *
 * The provider is exported and declares no manifest permission, because that would also lock out the adb shell. Every
 * `query` and `call` is instead checked by [CallerPolicy] on [Binder.getCallingUid]: Sonora itself always passes;
 * everybody else is denied (a [SecurityException]) while the user's "External control" switch is off; with it on the
 * shell and root pass and any other app needs [EXTERNAL_CONTROL_PERMISSION]. Nothing is written through the
 * `ContentProvider` write methods and no file is opened.
 *
 * Work runs through an [ExternalApiGateway]: on the main thread, with a bounded wait for the binder thread.
 */
class ExternalApiProvider : ContentProvider() {

    /** Set by tests; otherwise built on first use from the Hilt graph (never in [onCreate], which runs too early). */
    @Volatile
    internal var gateway: ExternalApiGateway? = null

    private val policy by lazy { CallerPolicy(ownUid = Process.myUid()) }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val api = authorised()
        return ApiBundles.toBundle(api.call(method, ApiBundles.toMap(extras)))
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val api = authorised()
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) }
        return ApiCursors.toCursor(api.query(uri.pathSegments, params), projection)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    /** The gateway, once the caller has passed [CallerPolicy]; throws [SecurityException] otherwise. */
    private fun authorised(): ExternalApiGateway {
        // Read first: the binder identity belongs to this thread, and nothing below may change it.
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val api = gateway ?: synchronized(this) { gateway ?: createGateway(requireNotNull(context)).also { gateway = it } }
        val verdict = policy.check(uid, api.isSwitchOn()) {
            requireNotNull(context).checkPermission(EXTERNAL_CONTROL_PERMISSION, pid, uid) == PackageManager.PERMISSION_GRANTED
        }
        if (verdict is CallerVerdict.Denied) throw SecurityException(verdict.reason.message)
        return api
    }

    private fun createGateway(context: Context): ExternalApiGateway {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ExternalApiEntryPoint::class.java)
        // Built on first use, on the worker (main) thread: the ports are singletons the app already creates there.
        val ports = lazy {
            ApiPorts(
                entry.playback(), entry.sleepTimer(), entry.tempo(), entry.crossfade(), entry.library(), entry.playlists(),
                entry.djMode(),
            )
        }
        val dispatcher = lazy { ApiDispatcher(ports.value) }
        val queries = lazy { ApiQueryEngine(ports.value) }
        return ExternalApiGateway(
            switchOn = { entry.preferences().preferences.first().externalControlEnabled },
            playbackConnected = flow { emitAll(ports.value.playback.state.map { it.isConnected }) },
            runCall = { method, extras -> dispatcher.value.call(method, extras) },
            runQuery = { segments, params -> queries.value.query(segments, params) },
            workDispatcher = Dispatchers.Main,
            limits = GatewayLimits(),
        )
    }
}

/** What [ExternalApiProvider] takes from the Hilt graph: the domain ports the API runs over, and the switch. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExternalApiEntryPoint {
    fun playback(): PlaybackController
    fun sleepTimer(): SleepTimer
    fun tempo(): PlaybackTempoRepository
    fun crossfade(): CrossfadeRepository
    fun library(): LibraryRepository
    fun playlists(): PlaylistRepository
    fun djMode(): DjModeSettings
    fun preferences(): PreferencesRepository
}
