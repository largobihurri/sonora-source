package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.DefaultCallerPolicy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SonoraExternalAccessTest {
    private val shell = Caller(CallerKind.SHELL, "shell")
    private val root = Caller(CallerKind.SHELL, "root")
    private val app = Caller(CallerKind.EXTERNAL_APP, "com.example.agent", "a".repeat(64))
    private val peer = Caller(CallerKind.PEER, "io.bihurri.kardio")
    private val refused = mutableListOf<Pair<Caller, Scope>>()
    private var switch = true
    private val access = SonoraExternalAccess({ switch }, { caller, scope -> refused += caller to scope })
    private val policy = DefaultCallerPolicy(access)

    private val every = listOf(MediaLibrary.ReadScope, MediaPlayback.ReadScope, MediaPlayback.ControlScope, MediaHistory.ReadScope, MediaArtwork.ReadScope)

    @Test
    fun `the master switch is the external control setting`() = runBlocking<Unit> {
        switch = false
        assertThat(access.isEnabled()).isFalse()
        switch = true
        assertThat(access.isEnabled()).isTrue()
    }

    @Test
    fun `the shell and root are granted every scope Sonora provides`() = runBlocking<Unit> {
        assertThat(access.grantedScopes(shell)).containsExactlyElementsIn(every)
        assertThat(access.grantedScopes(root)).containsExactlyElementsIn(every)
    }

    @Test
    fun `the granted scopes cover every operation and event of the four capabilities`() {
        val declared = listOf(MediaLibrary, MediaPlayback, MediaHistory, MediaArtwork).flatMap { capability ->
            capability.operations.map { it.scope } + capability.events.map { it.scope }
        }.toSet()

        assertThat(SonoraExternalAccess.SHELL_SCOPES).containsExactlyElementsIn(declared)
    }

    @Test
    fun `other apps and remote agents are granted nothing`() = runBlocking<Unit> {
        assertThat(access.grantedScopes(app)).isEmpty()
        assertThat(access.grantedScopes(Caller(CallerKind.REMOTE_AGENT, "agent"))).isEmpty()
    }

    @Test
    fun `with the switch on the shell may use every scope`() = runBlocking<Unit> {
        every.forEach { assertThat(policy.authorize(shell, it)).isTrue() }
    }

    @Test
    fun `with the switch off the shell is refused every scope`() = runBlocking<Unit> {
        switch = false
        every.forEach { assertThat(policy.authorize(shell, it)).isFalse() }
    }

    @Test
    fun `the cover art scope is refused to the shell with the switch off and to apps and agents always`() = runBlocking<Unit> {
        switch = false
        assertThat(policy.authorize(shell, MediaArtwork.ReadScope)).isFalse()
        switch = true
        assertThat(policy.authorize(shell, MediaArtwork.ReadScope)).isTrue()
        assertThat(policy.authorize(app, MediaArtwork.ReadScope)).isFalse()
        assertThat(policy.authorize(Caller(CallerKind.REMOTE_AGENT, "agent"), MediaArtwork.ReadScope)).isFalse()
        assertThat(refused.map { it.second }).contains(MediaArtwork.ReadScope)
    }

    @Test
    fun `an external app is refused even with the switch on`() = runBlocking<Unit> {
        every.forEach { assertThat(policy.authorize(app, it)).isFalse() }
    }

    @Test
    fun `signed peers and the app itself are allowed with the switch off`() = runBlocking<Unit> {
        switch = false
        assertThat(policy.authorize(peer, MediaPlayback.ControlScope)).isTrue()
        assertThat(policy.authorize(peer, MediaHistory.ReadScope)).isTrue()
        assertThat(policy.authorize(peer, MediaArtwork.ReadScope)).isTrue()
        assertThat(policy.authorize(Caller(CallerKind.SELF, "io.sonora.player"), MediaLibrary.ReadScope)).isTrue()
        assertThat(refused).isEmpty()
    }

    @Test
    fun `a refusal is recorded once per caller and scope`() = runBlocking<Unit> {
        val log = mutableListOf<Pair<Caller, Scope>>()
        val dedup = DeniedRequests { caller, scope -> log += caller to scope }

        repeat(5) { dedup.onDenied(app, MediaLibrary.ReadScope) }
        dedup.onDenied(app, MediaPlayback.ControlScope)
        dedup.onDenied(shell, MediaLibrary.ReadScope)

        assertThat(log).containsExactly(
            app to MediaLibrary.ReadScope,
            app to MediaPlayback.ControlScope,
            shell to MediaLibrary.ReadScope,
        )
    }

    @Test
    fun `the record of refusals does not grow without limit`() {
        val log = mutableListOf<Pair<Caller, Scope>>()
        val dedup = DeniedRequests { caller, scope -> log += caller to scope }

        repeat(DeniedRequests.MAX_ENTRIES + 50) { n -> dedup.onDenied(Caller(CallerKind.EXTERNAL_APP, "app.n$n"), MediaLibrary.ReadScope) }

        assertThat(log).hasSize(DeniedRequests.MAX_ENTRIES)
    }

    @Test
    fun `a refused external call reaches the record through the policy`() = runBlocking<Unit> {
        switch = false
        policy.authorize(app, MediaLibrary.ReadScope)
        policy.authorize(app, MediaLibrary.ReadScope)

        assertThat(refused).hasSize(2) // the access hands every refusal on; de-duplication is DeniedRequests' job
    }

    @Test
    fun `turning the switch off is a change, the starting value and repeats are not`() = runBlocking<Unit> {
        assertThat(flowOf(false, true, false, false, true, false).turnedOff().toList()).hasSize(2)
        assertThat(flowOf(false).turnedOff().toList()).isEmpty()
        assertThat(flowOf(true).turnedOff().toList()).isEmpty()
        assertThat(flowOf(true, true).turnedOff().toList()).isEmpty()
    }
}
