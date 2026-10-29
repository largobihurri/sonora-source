package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.JsonGate
import eus.bihurri.kora.core.KoraNode
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The watch's access decision: only the app itself and peers signed with the common key are served; the adb shell, other
 * apps and remote agents are refused for every scope, because the watch has no settings screen to grant anything from.
 * Goes through a real [KoraNode] and [JsonGate] (what the adb gate runs), with both capabilities registered.
 */
class WatchKoraPolicyTest {
    private val songs = listOf(song("1.opus"), song("2.opus"))
    private val player = FakePlayerPort(playerState(songs[0], WatchStatus.PAUSED))
    private val node = KoraNode("test.watch", policy = watchCallerPolicy()).apply {
        provide(MediaLibrary) { WatchLibraryHandlers({ songs }, { "Watch music" }).register(this) }
        provide(MediaPlayback) { WatchPlaybackHandlers(player, { songs }, { 1L }, Random(1)).register(this) }
    }
    private val gate = JsonGate(node)

    private val shell = Caller(CallerKind.SHELL, "shell")
    private val otherApp = Caller(CallerKind.EXTERNAL_APP, "com.example.other", signingCertificateSha256 = "ab".repeat(32))
    private val agent = Caller(CallerKind.REMOTE_AGENT, "tailnet:1")
    private val peer = Caller(CallerKind.PEER, "eus.bihurri.kardio")
    private val self = Caller(CallerKind.SELF, "io.sonora.player")

    private fun call(caller: Caller, capability: String, operation: String) =
        runBlocking { gate.call(caller, """{"capability":"$capability","operation":"$operation"}""") }

    @Test
    fun `the shell, other apps and remote agents are refused every operation`() {
        listOf(shell, otherApp, agent).forEach { caller ->
            listOf(
                "media.playback" to "get_state",
                "media.playback" to "play",
                "media.library" to "list_playlists",
            ).forEach { (capability, operation) ->
                val answer = call(caller, capability, operation)
                assertThat(answer).contains("\"ok\":false")
                assertThat(answer).contains("unauthorized")
            }
        }
    }

    @Test
    fun `a refused caller never reaches the player`() {
        call(shell, "media.playback", "play")
        call(otherApp, "media.playback", "next")
        call(agent, "media.playback", "pause")

        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `the app itself and signed peers are served`() {
        listOf(self, peer).forEach { caller ->
            assertThat(call(caller, "media.playback", "get_state")).contains("\"ok\":true")
            assertThat(call(caller, "media.library", "list_playlists")).contains("\"ok\":true")
        }
        call(peer, "media.playback", "play")
        assertThat(player.calls).containsExactly("play")
    }

    @Test
    fun `a refused caller cannot subscribe either`() {
        val request = SubscribeRequest("s1", "media.playback", CapabilityVersion(1, 0), "playback_state_changed")

        listOf(shell, otherApp, agent).forEach { caller ->
            val failure = assertThrows(KoraException::class.java) { runBlocking { node.subscribe(caller, request).first() } }
            assertThat(failure.error.code).isEqualTo(ErrorCode.UNAUTHORIZED)
        }
        assertThat(player.activeSubscriptions).isEqualTo(0)
    }
}
