package io.sonora.feature.cdjexport.export

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolumes
import io.sonora.feature.cdjexport.di.CdjExportIoDispatcher
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 * The removable volumes from [StorageManager.getStorageVolumes], read again on every media broadcast (mounted,
 * checking, eject, unmounted, removed) and, from Android 11, on every volume state callback. The phone's own storage
 * is left out. A read that fails reports nothing ([volumeReads]): "no volume" would tell an eject that the stick is gone.
 */
class AndroidRemovableVolumes @Inject constructor(
    @ApplicationContext private val context: Context,
    @CdjExportIoDispatcher private val io: CoroutineDispatcher,
) : RemovableVolumes {

    override fun volumes(): Flow<List<RemovableVolume>> = changes()
        .conflate()
        .volumeReads(::read)
        .flowOn(io)

    /** One signal now, then one per change Android reports. */
    private fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            MEDIA_ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val storage = context.getSystemService(StorageManager::class.java)
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storage != null) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    trySend(Unit)
                }
            }.also { storage.registerStorageVolumeCallback(context.mainExecutor, it) }
        } else {
            null
        }
        trySend(Unit)
        awaitClose {
            context.unregisterReceiver(receiver)
            if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                storage?.unregisterStorageVolumeCallback(callback)
            }
        }
    }

    /** The volumes now, `null` when they cannot be read. */
    private fun read(): List<RemovableVolume>? = try {
        context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
            .filter { it.isRemovable && !it.isPrimary && it.uuid != null }
            .map { volume ->
                val state = stateOf(volume.state)
                RemovableVolume(
                    uuid = volume.uuid!!,
                    name = volume.getDescription(context) ?: volume.uuid!!,
                    totalBytes = if (state == RemovableVolume.State.MOUNTED || state == RemovableVolume.State.MOUNTED_READ_ONLY) {
                        totalBytes(volume)
                    } else {
                        null
                    },
                    state = state,
                )
            }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot read the storage volumes", e)
        null
    }

    private fun totalBytes(volume: StorageVolume): Long? {
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
        val path = directory ?: File("/storage/${volume.uuid}")
        return try {
            StatFs(path.path).totalBytes
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val TAG = "SonoraExport"

        val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_CHECKING,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_UNMOUNTABLE,
            Intent.ACTION_MEDIA_NOFS,
        )

        fun stateOf(state: String?): RemovableVolume.State = when (state) {
            Environment.MEDIA_MOUNTED -> RemovableVolume.State.MOUNTED
            Environment.MEDIA_MOUNTED_READ_ONLY -> RemovableVolume.State.MOUNTED_READ_ONLY
            Environment.MEDIA_CHECKING -> RemovableVolume.State.CHECKING
            Environment.MEDIA_EJECTING -> RemovableVolume.State.EJECTING
            Environment.MEDIA_UNMOUNTED -> RemovableVolume.State.UNMOUNTED
            Environment.MEDIA_REMOVED -> RemovableVolume.State.REMOVED
            Environment.MEDIA_BAD_REMOVAL -> RemovableVolume.State.BAD_REMOVAL
            else -> RemovableVolume.State.OTHER
        }
    }
}

/**
 * The volumes [read] gives on each signal, each list once in a row. A read that fails (`null`) is left out rather than
 * reported as no volume, which would read as every stick let go (a false "safe to remove" during an eject).
 */
internal fun Flow<Unit>.volumeReads(read: () -> List<RemovableVolume>?): Flow<List<RemovableVolume>> =
    mapNotNull { read() }.distinctUntilChanged()
