package io.sonora.core.data.conversion

import android.content.Intent
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.saf.SafSourceFolderAccess
import io.sonora.core.data.conversion.saf.SafUris
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.port.OutputRootFolder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafConversionAccessTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val resolver = context.contentResolver

    private fun encode(id: String) = id.replace(":", "%3A").replace("/", "%2F")

    private fun tree(id: String) = "content://$AUTHORITY/tree/${encode(id)}"

    private fun document(treeId: String, id: String) = "${tree(treeId)}/document/${encode(id)}"

    private fun grants() = resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }

    @Test
    fun `tree uris are found for tree roots and documents below them only`() {
        assertThat(SafUris.treeOf(tree("primary:Music"))).isEqualTo(tree("primary:Music").toUri())
        assertThat(SafUris.treeOf(document("primary:Music", "primary:Music/Rock"))).isEqualTo(tree("primary:Music").toUri())
        assertThat(SafUris.treeOf("content://$AUTHORITY/document/primary%3AMusic%2Fa.flac")).isNull()
        assertThat(SafUris.treeOf("file:///sdcard/Music")).isNull()
    }

    @Test
    fun `a folder uri names the tree's root document or the document itself`() {
        assertThat(SafUris.folderDocument(tree("primary:Music")))
            .isEqualTo(document("primary:Music", "primary:Music").toUri())
        assertThat(SafUris.folderDocument(document("primary:Music", "primary:Music/Rock")))
            .isEqualTo(document("primary:Music", "primary:Music/Rock").toUri())
    }

    @Test
    fun `a library song's folder is the document above it, within its tree`() {
        assertThat(SafUris.parentFolder(document("primary:Music", "primary:Music/Rock/a.flac")))
            .isEqualTo(document("primary:Music", "primary:Music/Rock"))
        assertThat(SafUris.parentFolder(document("primary:Music", "primary:Music/a.flac")))
            .isEqualTo(document("primary:Music", "primary:Music"))
        assertThat(SafUris.parentFolder(document("primary:", "primary:a.flac")))
            .isEqualTo(document("primary:", "primary:"))
    }

    @Test
    fun `a song whose folder cannot be told has none`() {
        // Opaque ids (no path), the tree root itself, plain documents and other URIs.
        assertThat(SafUris.parentFolder(document("msf:12", "msf:345"))).isNull()
        assertThat(SafUris.parentFolder(document("primary:Music", "primary:Music"))).isNull()
        assertThat(SafUris.parentFolder("content://$AUTHORITY/document/primary%3AMusic%2Fa.flac")).isNull()
        assertThat(SafUris.parentFolder("file:///sdcard/Music/a.flac")).isNull()
    }

    @Test
    fun `write access persisted for a picked folder covers the folders below it`() = runBlocking<Unit> {
        val access = SafSourceFolderAccess(context)

        assertThat(access.canWrite(document("primary:Music", "primary:Music/Rock"))).isFalse()
        assertThat(access.persistWrite(tree("primary:Music"))).isTrue()

        assertThat(grants()).containsExactly(Triple(tree("primary:Music"), true, true))
        assertThat(access.canWrite(document("primary:Music", "primary:Music/Rock"))).isTrue()
        assertThat(access.canWrite(tree("primary:Music"))).isTrue()
        assertThat(access.canWrite(document("primary:Podcasts", "primary:Podcasts"))).isFalse()
        assertThat(access.canWrite("content://$AUTHORITY/document/primary%3AMusic")).isFalse()
    }

    @Test
    fun `read-only access does not allow replacing`() = runBlocking<Unit> {
        resolver.takePersistableUriPermission(tree("primary:Music").toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertThat(SafSourceFolderAccess(context).canWrite(document("primary:Music", "primary:Music/Rock"))).isFalse()
    }

    private fun <T> withRoot(block: suspend (DataStoreConversionOutputRoot) -> T): T = runBlocking {
        val job = Job()
        val store = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { File(folder.root, "settings.preferences_pb") }
        try {
            block(DataStoreConversionOutputRoot(context, store))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `the output folder is unset until picked`() {
        assertThat(withRoot { it.root.first() }).isNull()
    }

    @Test
    fun `picking the output folder keeps read and write access and survives a restart`() {
        val picked = withRoot { it.set(tree("primary:Music/DJ")) }

        assertThat(picked).isEqualTo(OutputRootFolder(tree("primary:Music/DJ"), "Music/DJ"))
        assertThat(grants()).containsExactly(Triple(tree("primary:Music/DJ"), true, true))
        assertThat(withRoot { it.root.first() }).isEqualTo(picked)
    }

    @Test
    fun `an output folder whose access was revoked reads as unset`() {
        withRoot { it.set(tree("primary:Music/DJ")) }
        resolver.releasePersistableUriPermission(
            tree("primary:Music/DJ").toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        assertThat(withRoot { it.root.first() }).isNull()
    }

    @Test
    fun `picking again a folder whose access was revoked shows it to a screen already watching`() {
        val dj = tree("primary:Music/DJ")
        withRoot { it.set(dj) }
        resolver.releasePersistableUriPermission(
            dj.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        val seen = withRoot { store ->
            coroutineScope {
                val latest = MutableStateFlow<Any?>(NOTHING)
                val watcher = launch(Dispatchers.IO) { store.root.collect { latest.value = it } }
                withTimeout(5_000) { latest.first { it == null } }
                // Same folder, same stored values: only the new grant changed.
                store.set(dj)
                withTimeoutOrNull(3_000) { latest.first { it != null } }.also { watcher.cancel() }
            }
        }

        assertThat(seen).isEqualTo(OutputRootFolder(dj, "Music/DJ"))
    }

    private companion object {
        const val NOTHING = "nothing yet"
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
