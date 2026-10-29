package io.sonora.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.log.redactForLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** The documents side of an archive import: an archive's name, and the persistable grants an import no longer needs. */
interface ImportDocuments {
    /** [uri]'s display name as its provider tells it, read off the main thread; `null` when it does not tell. */
    suspend fun displayName(uri: String): String?

    /** Gives back the persistable read grant kept on archive [uri]. */
    fun releaseArchive(uri: String)

    /** Gives back the persistable read and write grant kept on the picked folder [treeUri]. */
    fun releaseFolder(treeUri: String)
}

/**
 * Where imports are followed to their end: as long as the process lives, not a screen, so an extraction that ends
 * after its activity is gone still joins the library, recreates its playlist and gives back its grants. Main-thread
 * confined, like a view model's scope.
 */
@Singleton
class ImportJobScope internal constructor(override val coroutineContext: CoroutineContext) : CoroutineScope {
    @Inject
    constructor() : this(SupervisorJob() + Dispatchers.Main.immediate)
}

/** [ImportDocuments] through the content resolver. */
internal class ContentResolverImportDocuments @Inject constructor(@ApplicationContext context: Context) : ImportDocuments {

    private val resolver = context.contentResolver

    override suspend fun displayName(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            resolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() }
        } catch (e: RuntimeException) {
            // SecurityException, IllegalArgumentException or a provider's own failure: the caller falls back.
            Log.w(TAG, "Cannot read the archive's name", e)
            null
        }
    }

    override fun releaseArchive(uri: String) = release(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun releaseFolder(treeUri: String) =
        release(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun release(uri: String, flags: Int) {
        try {
            resolver.releasePersistableUriPermission(uri.toUri(), flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot release the grant on ${redactForLog(uri)}", e)
        }
    }

    private companion object {
        const val TAG = "SonoraImport"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface ImportDocumentsModule {
    @Binds
    fun bindImportDocuments(documents: ContentResolverImportDocuments): ImportDocuments
}
