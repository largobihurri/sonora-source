package io.sonora.core.playback.loop

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.playback.SessionPlayer
import io.sonora.core.playback.audio.TestAudioFiles
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

/**
 * The session's real ExoPlayer with the loop sink in DJ mode, on a pretend AudioTrack that keeps what it is written: a
 * loop made from the hub repeats in the track's data with an exact period, and a loop held at the end of the song
 * keeps the player playing instead of ending.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoopPlaybackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<ExoPlayer>()
    private val written = ByteArrayOutputStream()
    private val audioData = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
        synchronized(written) { written.write(data) }
        // The pretend AudioTrack takes everything at once and "plays" it at once: pace the playback thread in real time
        // (the player's position is what the track has been given), as a real AudioTrack would.
        Thread.sleep(maxOf(1L, data.size / 4 * 1_000L / 48_000))
    }
    private val hub = LoopHub().apply { enabled = true }
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())

    init {
        hub.listener = object : LoopHubListener {
            override fun onSnapshot(snapshot: LoopSnapshot) {
                events += "snapshot $snapshot"
            }

            override fun onNeedsSeek(inMs: Double) {
                events += "needs seek to $inMs"
            }

            override fun onTooLong(inMs: Double, outMs: Double) {
                events += "too long $inMs-$outMs"
            }

            override fun onRefused(refusal: LoopRefusal) {
                events += "refused $refusal"
            }
        }
    }

    @After
    fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(audioData)
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 16-bit stereo, every frame different: left = frame - 30000, right = its negative. */
    private fun song(frames: Int): ByteArray {
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            data.putShort((i - 30_000).toShort())
            data.putShort((30_000 - i).toShort())
        }
        return TestAudioFiles.wav(2, 48_000, 16, data.array(), formatType = 1)
    }

    private fun start(frames: Int): ExoPlayer {
        val path = folder.newFile("song.wav").apply { writeBytes(song(frames)) }
        ShadowAudioTrack.addAudioDataListener(audioData)
        val player = SessionPlayer.build(context, loops = hub) { io.sonora.core.domain.equalizer.EqualizerSettings() }.also { players += it }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(path)))
        player.prepare()
        player.play()
        return player
    }

    private fun bytesWritten() = synchronized(written) { written.size() }

    /** Lets the player run until [condition] holds (or fails after 90 s). */
    private fun runUntil(player: ExoPlayer, what: String, condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (!condition() && player.playerError == null && System.nanoTime() < deadline) {
            looper.idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
        }
        assertThat(player.playerError).isNull()
        check(condition()) { "timed out waiting for $what; state ${player.playbackState}, position ${player.currentPosition}, bytes ${bytesWritten()}, events ${synchronized(events) { events.toList() }}" }
    }

    /** The left sample of frame [index] of everything the AudioTrack was given. */
    private fun leftAt(data: ByteArray, index: Int): Int =
        ByteBuffer.wrap(data, index * 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))

    /**
     * Asks for a one beat loop (500 ms, 24000 frames) at the start of the song and waits for it to engage: the loop is
     * ahead of the audio when it is set, so it is armed, the song plays on to its Out and the loop takes over there.
     * (The pretend AudioTrack's position is not a real clock, so the loop is made where the song starts.)
     */
    private fun beatLoop(player: ExoPlayer): LoopSnapshot {
        hub.context = LoopContext(LoopQuantizer(grid, durationMs = 2_000.0), quantize = true)
        runUntil(player, "playback") { bytesWritten() > 4 * 2_000 && hub.snapshot.available }
        hub.post(LoopRequest.BeatLoop(1.0))
        runUntil(player, "the loop") { hub.snapshot.engaged }
        return hub.snapshot
    }

    @Test
    fun `a beat loop repeats in the AudioTrack's data with an exact period and holds the song`() {
        val player = start(frames = 96_000)
        val loop = beatLoop(player)
        assertThat(loop.loopOutMs!! - loop.loopInMs!!).isWithin(0.05).of(500.0)
        val length = 24_000
        // Several iterations of audio in the track, well past the end of the 2 s song.
        runUntil(player, "iterations") { bytesWritten() > 4 * (96_000 + 6 * length) }

        val data = synchronized(written) { written.toByteArray() }
        val frames = data.size / 4
        // The song plays as it is up to the loop's Out (after the fade-in the sink gives the start of a stream)...
        for (i in 2_000 until length step 97) assertThat(leftAt(data, i)).isEqualTo(i - 30_000)
        // ...then repeats with an exact period: each frame equals the one a loop earlier, over the last three iterations.
        for (i in frames - 3 * length until frames - 8) assertThat(leftAt(data, i)).isEqualTo(leftAt(data, i - length))
        // The decoder stalled on the loop, so the song never reached its end and the player is still playing.
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun `the player's position stays inside the loop once it repeats`() {
        val player = start(frames = 96_000)
        val loop = beatLoop(player)
        runUntil(player, "the loop heard") { hub.audible.looping }
        runUntil(player, "iterations") { bytesWritten() > 4 * (96_000 + 3 * 24_000) }

        val looper = shadowOf(Looper.getMainLooper())
        val positions = ArrayList<Long>()
        repeat(30) {
            looper.idleFor(Duration.ofMillis(30))
            Thread.sleep(30)
            positions += player.currentPosition
        }
        assertThat(hub.audible.inMs).isWithin(0.05).of(loop.loopInMs!!)
        assertThat(hub.audible.outMs).isWithin(0.05).of(loop.loopOutMs!!)
        // However long the loop has played, the player reports a position of the song, inside the loop.
        for (position in positions) assertThat(position.toDouble()).isAtMost(loop.loopOutMs!! + 150.0)
        assertThat(player.duration).isEqualTo(2_000L)
    }
}
