package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime, advanceTimeBy, UnconfinedTestDispatcher
class ControllerConnectionTest {

    private class Fake(val id: Int) {
        var alive = true
    }

    private var attempts = 0
    private val dropped = mutableListOf<Fake>()
    private val errors = mutableListOf<Throwable>()
    private val droppedCommands = mutableListOf<Int>()
    private val ran = mutableListOf<Pair<Int, Long>>()
    private var onConnected: (Fake) -> Unit = {}

    private fun TestScope.connection(
        retryDelaysMs: List<Long> = listOf(100L, 400L),
        scope: CoroutineScope = this,
        connect: suspend () -> Fake = { Fake(++attempts) },
    ) = ControllerConnection(
        scope = scope,
        connect = connect,
        isAlive = { it.alive },
        onDropped = { dropped += it },
        onConnected = { onConnected(it) },
        retryDelaysMs = retryDelaysMs,
        onError = { errors += it },
        onCommandsDropped = { droppedCommands += it },
    )

    /** Issues command [id], recording when it ran. */
    private fun TestScope.issue(c: ControllerConnection<Fake>, id: Int) = c.execute { ran += id to currentTime }

    /** The controller a command would run on now, after any connection round. */
    private fun TestScope.controllerOf(c: ControllerConnection<Fake>): Fake? {
        var result: Fake? = null
        c.execute { result = it }
        advanceUntilIdle()
        return result
    }

    @Test
    fun `failed round drops its commands after bounded retries instead of hanging`() = runTest {
        val c = connection { attempts++; error("service unavailable") }
        issue(c, 1)
        advanceUntilIdle()
        assertThat(ran).isEmpty()
        assertThat(attempts).isEqualTo(3)
        assertThat(errors).hasSize(3)
        assertThat(droppedCommands).containsExactly(1)
        assertThat(currentTime).isEqualTo(500L)
    }

    @Test
    fun `commands during a failing round share it and are dropped when it fails`() = runTest {
        val c = connection { attempts++; error("service unavailable") }
        issue(c, 1)
        advanceTimeBy(50)
        issue(c, 2)
        advanceTimeBy(200)
        issue(c, 3)
        advanceUntilIdle()
        assertThat(attempts).isEqualTo(3)
        assertThat(currentTime).isEqualTo(500L)
        assertThat(droppedCommands).containsExactly(3)
        advanceTimeBy(10_000)
        assertThat(ran).isEmpty()
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `commands during a successful round run in issue order as soon as it connects`() = runTest {
        val c = connection { delay(50); Fake(++attempts) }
        issue(c, 1)
        advanceTimeBy(10)
        issue(c, 2)
        advanceTimeBy(10)
        issue(c, 3)
        advanceUntilIdle()
        assertThat(ran).containsExactly(1 to 50L, 2 to 50L, 3 to 50L).inOrder()
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `retries until a connection succeeds`() = runTest {
        val c = connection { if (++attempts < 2) error("not yet") else Fake(attempts) }
        assertThat(controllerOf(c)?.id).isEqualTo(2)
    }

    @Test
    fun `a failed round does not prevent a later command from connecting`() = runTest {
        var fail = true
        val c = connection(retryDelaysMs = emptyList()) { attempts++; if (fail) error("down") else Fake(attempts) }
        assertThat(controllerOf(c)).isNull()
        fail = false
        assertThat(controllerOf(c)?.id).isEqualTo(2)
    }

    @Test
    fun `reuses the live controller and runs commands immediately`() = runTest {
        val c = connection()
        val first = controllerOf(c)
        issue(c, 1)
        assertThat(ran).containsExactly(1 to currentTime)
        assertThat(controllerOf(c)).isSameInstanceAs(first)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `controller whose setup fails is released and the round retries`() = runTest {
        onConnected = { if (it.id == 1) error("publish failed") }
        val c = connection()
        assertThat(controllerOf(c)?.id).isEqualTo(2)
        assertThat(dropped.map { it.id }).containsExactly(1)
        assertThat(errors).hasSize(1)
    }

    @Test
    fun `controller whose setup always fails is never leaked`() = runTest {
        onConnected = { error("publish failed") }
        val c = connection()
        assertThat(controllerOf(c)).isNull()
        assertThat(dropped.map { it.id }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `a round finished inline by an immediate dispatcher does not block later rounds`() = runTest {
        val c = connection(scope = TestScope(UnconfinedTestDispatcher(testScheduler)))
        issue(c, 1)
        val first = controllerOf(c)!!
        c.invalidate(first)
        issue(c, 2)
        assertThat(ran.map { it.first }).containsExactly(1, 2).inOrder()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `invalidated controller is dropped once and the next command reconnects`() = runTest {
        val c = connection()
        val first = controllerOf(c)!!
        c.invalidate(first)
        c.invalidate(first)
        assertThat(dropped).containsExactly(first)
        assertThat(controllerOf(c)?.id).isEqualTo(2)
    }

    @Test
    fun `stale invalidation does not drop the newer controller`() = runTest {
        val c = connection()
        val first = controllerOf(c)!!
        c.invalidate(first)
        val second = controllerOf(c)!!
        c.invalidate(first)
        assertThat(dropped).containsExactly(first)
        assertThat(controllerOf(c)).isSameInstanceAs(second)
    }

    @Test
    fun `controller that is no longer alive is dropped and replaced`() = runTest {
        val c = connection()
        val first = controllerOf(c)!!
        first.alive = false
        assertThat(controllerOf(c)?.id).isEqualTo(2)
        assertThat(dropped).containsExactly(first)
    }

    /** A scope whose uncaught failures are recorded instead of failing the test. */
    private fun TestScope.isolatedScope(uncaught: MutableList<Throwable>, job: Job = SupervisorJob()) =
        CoroutineScope(job + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e })

    @Test
    fun `a round ended by an Error drops and reports its commands instead of replaying them later`() = runTest {
        val uncaught = mutableListOf<Throwable>()
        var fail = true
        val c = connection(scope = isolatedScope(uncaught)) {
            attempts++
            if (fail) throw LinkageError("player library missing") else Fake(attempts)
        }
        issue(c, 1)
        advanceUntilIdle()
        assertThat(droppedCommands).containsExactly(1)
        assertThat(uncaught.single()).isInstanceOf(LinkageError::class.java)

        fail = false
        issue(c, 2)
        advanceUntilIdle()
        assertThat(ran.map { it.first }).containsExactly(2)
    }

    @Test
    fun `a cancelled round drops and reports its commands`() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = isolatedScope(uncaught)
        val c = connection(scope = scope) { delay(1_000); Fake(++attempts) }
        issue(c, 1)
        issue(c, 2)
        advanceTimeBy(100)
        scope.cancel()
        advanceUntilIdle()
        assertThat(ran).isEmpty()
        assertThat(droppedCommands).containsExactly(2)
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun `a connect future cancelled underneath the round drops its commands and the next command reconnects`() = runTest {
        val uncaught = mutableListOf<Throwable>()
        var cancelled = true
        val c = connection(scope = isolatedScope(uncaught)) {
            attempts++
            // What awaiting a cancelled MediaController.buildAsync() future throws.
            if (cancelled) throw java.util.concurrent.CancellationException("future cancelled") else Fake(attempts)
        }
        issue(c, 1)
        advanceUntilIdle()
        assertThat(droppedCommands).containsExactly(1)

        cancelled = false
        issue(c, 2)
        advanceUntilIdle()
        assertThat(ran.map { it.first }).containsExactly(2)
    }
}
