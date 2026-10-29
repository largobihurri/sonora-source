package io.sonora.core.playback

import android.app.Application
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.ApiResult
import io.sonora.core.api.EXTERNAL_CONTROL_PERMISSION
import io.sonora.core.api.Page
import io.sonora.core.api.TrackRow
import io.sonora.core.playback.api.ExternalApiGateway
import io.sonora.core.playback.api.GatewayLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * Who may reach the external API, checked on the calling uid: the user's switch, the adb shell, root, apps with and
 * without the custom permission, and Sonora itself. A denied caller gets a [SecurityException] and nothing runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalApiProviderTest {

    private val context = RuntimeEnvironment.getApplication()
    private val ownUid = Process.myUid()
    private val appUid = ownUid + 1
    private val authority = "${context.packageName}.api"
    private lateinit var provider: ExternalApiProvider

    private var switchOn = false
    private val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val queries = mutableListOf<Pair<List<String>, Map<String, Any?>>>()
    private var queryResult: ApiQueryResult = ApiQueryResult.Tracks(Page(listOf(trackRow(1)), 1, 50, 0))
    private var callResult: ApiResult = ApiResult.Ok(mapOf("count" to 2))

    private fun trackRow(id: Long) = TrackRow(id, "Title $id", "Artist", "Album", 7, 3, 180_000, 1, null, 1999)

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(ExternalApiProvider::class.java).create(authority).get()
        provider.gateway = ExternalApiGateway(
            switchOn = { switchOn },
            playbackConnected = flowOf(true),
            runCall = { method, extras -> calls += method to extras; callResult },
            runQuery = { segments, params -> queries += segments to params; queryResult },
            workDispatcher = Dispatchers.Default,
            limits = GatewayLimits(callBudgetMs = 5_000, connectWaitMs = 200, switchBudgetMs = 1_000),
        )
        callerIs(appUid)
    }

    @After
    fun tearDown() = ShadowBinder.reset()

    private fun callerIs(uid: Int) {
        ShadowBinder.setCallingUid(uid)
        ShadowBinder.setCallingPid(4242)
    }

    private fun grantPermissionToCaller() {
        shadowOf(context as Application).grantPermissions(4242, appUid, EXTERNAL_CONTROL_PERMISSION)
    }

    private fun call(method: String = "play", extras: Bundle? = Bundle()) = provider.call(method, "ignored", extras)!!

    private fun query(path: String = "tracks") = provider.query(Uri.parse("content://$authority/$path"), null, null, null, null)!!

    @Test
    fun `Sonora's own uid is allowed with the switch off`() {
        callerIs(ownUid)
        switchOn = false

        assertThat(call().getBoolean("ok")).isTrue()
        assertThat(query().count).isEqualTo(1)
    }

    @Test
    fun `with the switch off every other caller is denied, the shell, root and a permission holder included`() {
        switchOn = false
        grantPermissionToCaller()

        for (uid in listOf(appUid, 2000, 0)) {
            callerIs(uid)
            val call = assertThrows(SecurityException::class.java) { call() }
            val query = assertThrows(SecurityException::class.java) { query() }
            assertThat(call).hasMessageThat().contains("External control is off")
            assertThat(query).hasMessageThat().contains("External control is off")
        }
        assertThat(calls).isEmpty()
        assertThat(queries).isEmpty()
    }

    @Test
    fun `with the switch on the adb shell is allowed without the permission`() {
        switchOn = true
        callerIs(2000)

        assertThat(call().getBoolean("ok")).isTrue()
        assertThat(query().count).isEqualTo(1)
    }

    @Test
    fun `with the switch on root is allowed without the permission`() {
        switchOn = true
        callerIs(0)

        assertThat(call().getBoolean("ok")).isTrue()
        assertThat(query().count).isEqualTo(1)
    }

    @Test
    fun `with the switch on an app holding the permission is allowed`() {
        switchOn = true
        grantPermissionToCaller()

        assertThat(call().getBoolean("ok")).isTrue()
        assertThat(query().count).isEqualTo(1)
    }

    @Test
    fun `with the switch on an app without the permission is denied and told which one it needs`() {
        switchOn = true

        val call = assertThrows(SecurityException::class.java) { call() }
        val query = assertThrows(SecurityException::class.java) { query() }

        assertThat(call).hasMessageThat().contains(EXTERNAL_CONTROL_PERMISSION)
        assertThat(query).hasMessageThat().contains(EXTERNAL_CONTROL_PERMISSION)
        assertThat(calls).isEmpty()
        assertThat(queries).isEmpty()
    }

    @Test
    fun `the permission is granted per app, so another uid with the permission does not help this one`() {
        switchOn = true
        shadowOf(context as Application).grantPermissions(4242, appUid + 5, EXTERNAL_CONTROL_PERMISSION)

        assertThrows(SecurityException::class.java) { call() }
    }

    @Test
    fun `turning the switch off takes effect on the next call`() {
        switchOn = true
        callerIs(2000)
        assertThat(call().getBoolean("ok")).isTrue()

        switchOn = false

        assertThrows(SecurityException::class.java) { call() }
        assertThrows(SecurityException::class.java) { query() }
    }

    @Test
    fun `a switch that cannot be read denies every other caller`() {
        provider.gateway = ExternalApiGateway(
            switchOn = { error("store unreadable") },
            playbackConnected = flowOf(true),
            runCall = { _, _ -> callResult },
            runQuery = { _, _ -> queryResult },
            workDispatcher = Dispatchers.Default,
            limits = GatewayLimits(5_000, 200, 1_000),
        )
        callerIs(2000)

        assertThrows(SecurityException::class.java) { call() }
    }

    @Test
    fun `a call hands its method and extras to the core and answers with the result bundle`() {
        switchOn = true
        callerIs(2000)
        val extras = Bundle().apply {
            putLong("id", 12)
            putBoolean("shuffle", true)
            putLongArray("ids", longArrayOf(1, 2))
        }

        val result = call("play_album", extras)

        assertThat(calls).hasSize(1)
        assertThat(calls[0].first).isEqualTo("play_album")
        assertThat(calls[0].second).containsExactly("id", 12L, "shuffle", true, "ids", listOf(1L, 2L))
        assertThat(result.getBoolean("ok")).isTrue()
        assertThat(result.getInt("count")).isEqualTo(2)
    }

    @Test
    fun `a call without extras runs with an empty map`() {
        switchOn = true
        callerIs(2000)

        provider.call("pause", null, null)

        assertThat(calls).containsExactly("pause" to emptyMap<String, Any?>())
    }

    @Test
    fun `a failed command is an ok false bundle, not an exception`() {
        switchOn = true
        callerIs(2000)
        callResult = ApiResult.Failed(ApiError.NotFound("album", 12))

        val result = call("play_album")

        assertThat(result.getBoolean("ok", true)).isFalse()
        assertThat(result.getString("error")).isEqualTo("not_found")
    }

    @Test
    fun `a query hands its path segments and parameters to the core and answers with a cursor`() {
        switchOn = true
        callerIs(2000)

        val cursor = provider.query(
            Uri.parse("content://$authority/albums/3/tracks?limit=5&offset=2&q=ac%2Fdc"), null, null, null, null,
        )!!

        assertThat(queries).containsExactly(
            listOf("albums", "3", "tracks") to mapOf("limit" to "5", "offset" to "2", "q" to "ac/dc"),
        )
        assertThat(cursor.count).isEqualTo(1)
        assertThat(cursor.columnNames.toList()).contains("title")
    }

    @Test
    fun `a failed query is an exception naming the stable code`() {
        switchOn = true
        callerIs(2000)
        queryResult = ApiQueryResult.Failed(ApiError.UnknownQuery)

        val error = assertThrows(IllegalArgumentException::class.java) { query("nope") }

        assertThat(error).hasMessageThat().startsWith("unknown_query: ")
    }

    @Test
    fun `the projection is honoured`() {
        switchOn = true
        callerIs(2000)

        val cursor = provider.query(Uri.parse("content://$authority/tracks"), arrayOf("id", "title"), null, null, null)!!

        assertThat(cursor.columnNames.toList()).containsExactly("id", "title").inOrder()
    }

    @Test
    fun `nothing can be inserted, updated or deleted and there is no file to open`() {
        switchOn = true
        callerIs(2000)
        val uri = Uri.parse("content://$authority/tracks")

        assertThat(provider.insert(uri, null)).isNull()
        assertThat(provider.update(uri, null, null, null)).isEqualTo(0)
        assertThat(provider.delete(uri, null, null)).isEqualTo(0)
        assertThat(provider.getType(uri)).isNull()
        assertThrows(java.io.FileNotFoundException::class.java) { provider.openFile(uri, "r") }
    }

    @Test
    fun `the manifest exports the provider under the api authority with no permission of its own`() {
        val info = context.packageManager.resolveContentProvider(authority, 0)

        assertThat(info).isNotNull()
        assertThat(info!!.exported).isTrue()
        assertThat(info.name).isEqualTo(ExternalApiProvider::class.java.name)
        assertThat(info.readPermission).isNull()
        assertThat(info.writePermission).isNull()
    }

    @Test
    fun `the manifest declares the custom permission as dangerous so the user grants it per app`() {
        val info = context.packageManager.getPermissionInfo(EXTERNAL_CONTROL_PERMISSION, 0)

        assertThat(info.protection).isEqualTo(PermissionInfo.PROTECTION_DANGEROUS)
    }
}
