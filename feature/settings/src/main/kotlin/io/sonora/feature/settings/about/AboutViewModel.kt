package io.sonora.feature.settings.about

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.watchport.WatchSyncController
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The installed app as the system reports it: its label and version (never hard-coded). */
data class InstalledApp(val name: String, val versionName: String?, val versionCode: Long)

/**
 * Settings › About: the installed app's name and version, and the licence notices of the components this build
 * carries, read from the packaged assets.
 */
@HiltViewModel
internal class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    watch: WatchSyncController,
) : ViewModel() {

    val app: InstalledApp = installedApp(context)

    /**
     * Whether this is the `full` build, which carries Google Play services; the watch link is only there, so its
     * availability tells the builds apart.
     */
    val fullBuild: Boolean = watch.isAvailable

    private val _notices = MutableStateFlow<List<LicenceNotice>?>(null)

    /** The notices of the components this build carries, in the index's order; null while they load. */
    val notices: StateFlow<List<LicenceNotice>?> = _notices.asStateFlow()

    init {
        viewModelScope.launch {
            _notices.value = withContext(Dispatchers.IO) {
                try {
                    LicenceIndex.shownIn(LicenceIndex.parse(readAsset(LicenceIndex.ASSET)), fullBuild)
                } catch (e: IOException) {
                    Log.w(TAG, "Couldn't read the licence index", e)
                    emptyList()
                }
            }
        }
    }

    /** The full licence text at [path] (under `licences/`), or null when it cannot be read. */
    suspend fun licenceText(path: String): String? = withContext(Dispatchers.IO) {
        try {
            readAsset("licences/$path")
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read the licence text $path", e)
            null
        }
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "SonoraAbout"

        fun installedApp(context: Context): InstalledApp {
            val manager = context.packageManager
            val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
            return InstalledApp(
                name = context.applicationInfo.loadLabel(manager).toString(),
                versionName = info.versionName,
                versionCode = info.longVersionCode,
            )
        }
    }
}
