package io.sonora.core.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.car.CarLibraryTree
import io.sonora.core.domain.library.plays.PlayHistoryRepository
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.ReplayGainRepository
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.BitPerfectMode
import io.sonora.core.domain.port.EqualizerRepository
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.stats.ListeningHistory
import io.sonora.core.playback.clock.LivePlaybackClock
import io.sonora.core.playback.crossfade.CrossfadeController
import io.sonora.core.playback.crossfade.FadeGain
import io.sonora.core.playback.equalizer.LiveEqualizer
import io.sonora.core.playback.interruption.CallInterruptionMonitor
import io.sonora.core.playback.levels.LiveBandLevels
import io.sonora.core.playback.dub.DubController
import io.sonora.core.playback.loop.LoopController
import io.sonora.core.playback.output.AUDIO_OUT_TAG
import io.sonora.core.playback.output.AudioManagerMixerAttributes
import io.sonora.core.playback.output.BitPerfectOutput
import io.sonora.core.playback.output.UsbConnectionLogger
import io.sonora.core.playback.output.lockedTo
import io.sonora.core.playback.replaygain.LiveReplayGain
import java.util.Optional
import javax.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Background playback host: ExoPlayer + MediaSession, with Sonora's notification (small icon, "Playback" channel),
 * Shuffle/Repeat in the system media controls and resumption of the last queue (see [SonoraSessionCallback]). The
 * session is a media library, so Android Auto can browse it ([SonoraLibraryCallback]).
 *
 * The service shares the app's process, so it keeps the process's queue owner ([Media3PlaybackController]) connected
 * whenever it runs, also when it was started without the app (Bluetooth play, the system's resumption controls): shuffle
 * from any controller then reorders the real queue, and the queue keeps being recorded for the next resumption.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var playback: Media3PlaybackController

    @Inject lateinit var snapshots: PlaybackSnapshotStore

    @Inject lateinit var equalizerSettings: EqualizerRepository

    @Inject lateinit var playHistory: PlayHistoryRepository

    /** Library > Statistics: how long each playback was heard. */
    @Inject lateinit var listeningHistory: ListeningHistory

    @Inject lateinit var tempo: PlaybackTempoRepository

    @Inject lateinit var bandLevels: LiveBandLevels

    @Inject lateinit var playbackClock: LivePlaybackClock

    @Inject lateinit var sleepTimer: SleepTimerService

    @Inject lateinit var replayGainSettings: ReplayGainRepository

    @Inject lateinit var crossfadeSettings: CrossfadeRepository


    @Inject lateinit var carLibrary: CarLibraryTree

    @Inject lateinit var audioOutputs: AudioOutputMonitor

    @Inject lateinit var preferences: PreferencesRepository

    @Inject lateinit var djMode: DjModeSettings

    /** The DJ loop: its audio sink goes into the player, and it follows the player's song and seeks. */
    @Inject lateinit var loops: LoopController

    /** The DUB stage: its audio sink goes into the player, and it follows the player's song, focus and routing. */
    @Inject lateinit var dub: DubController
    @Inject lateinit var sessionRouter: Optional<SessionRouter>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Play counts are written here, not on [scope]: the last play still lands after the service stops (briefly). */
    private val playCountWrites = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var callInterruption: CallInterruptionMonitor? = null
    private var playCounts: PlayCountRecorder? = null
    private var crossfade: CrossfadeController? = null
    private var routing: RoutedPlayer? = null
    private var releaseBitPerfect: (() -> Unit)? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(notificationProvider())
        // The player's equalizer follows the stored settings live: each change reaches the next audio buffer.
        val equalizer = LiveEqualizer()
        scope.launch { equalizerSettings.settings.collect { equalizer.settings = it } }
        // A USB audio device appearing or leaving is logged with what it reports (`adb logcat -s SonoraAudioOut`), so a
        // DAC can be checked, bit-perfect offer included, without opening the app.
        scope.launch { UsbConnectionLogger { Log.i(AUDIO_OUT_TAG, it) }.collect(audioOutputs.snapshots) }
        // Bit-perfect USB output (Android 14+): armed while the user's switch is on and the USB device heard offers it. Armed,
        // the sink hands every stream on untouched (no equalizer, no volume normalisation), the AudioTrack opens in the
        // device's own format, and speed, pitch and crossfade are held neutral below (the stored settings are untouched).
        val bitPerfect = BitPerfectOutput(audioOutputs, AudioManagerMixerAttributes(this))
        releaseBitPerfect = bitPerfect::release
        val armed = MutableStateFlow(false)
        scope.launch {
            combine(preferences.preferences.map { it.audio.bitPerfectUsb }.distinctUntilChanged(), audioOutputs.snapshots, BitPerfectMode::armed)
                .distinctUntilChanged()
                .collect { now ->
                    bitPerfect.armed = now
                    armed.value = now
                    if (!now) bitPerfect.release()
                    Log.i(AUDIO_OUT_TAG, if (now) "Bit-perfect output armed" else "Bit-perfect output not armed")
                }
        }
        // Volume normalisation too: a change reaches the next buffer of the song playing.
        val replayGain = LiveReplayGain()
        scope.launch { replayGainSettings.settings.collect { replayGain.settings = it } }
        // A crossfade's level for this player, applied at its sink so its volume (which controllers see) never moves.
        val fadeGain = FadeGain()
        val player = SessionPlayer.build(this, bandLevels, replayGain = { replayGain.settings }, fadeGain = fadeGain, bitPerfect = bitPerfect, loops = loops.hub, dub = dub.hub) { equalizer.settings }
        // DJ loops: sample-accurate, in the player's audio path while DJ mode is on. The A-B loop is off meanwhile.
        loops.attach(player, scope)
        // DUB mode keeps the DJ deck's services, so the A-B loop is off there too.
        scope.launch { combine(djMode.enabled, dub.holdsCrossfade) { dj, dubOn -> dj || dubOn }.collect { playback.setAbLoopAllowed(!it) } }
        // Speed and pitch follow the stored setting too, from the start and on every change.
        followTempo(player, tempo.tempo.lockedTo(PlaybackTempo.DEFAULT, armed), scope)
        // The DJ screen reads this local player's clock on every frame (never the Cast player's: that position is a receiver's).
        playbackClock.attach(player)
        // Controllers (the app's File Info sheet) see what the player decodes and outputs, per queue entry.
        player.addAnalyticsListener(DecodedFormatReporter { formats -> session?.setSessionExtras(DecodedFormatExtras.toBundle(formats)) })
        // Debug builds log formats and AudioTrack setup (`adb logcat -s SonoraPlayer`), e.g. to check float output.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) player.addAnalyticsListener(EventLogger(LOG_TAG))
        // In the `full` build, with Google Play services, the session can move to a Cast device and back (B12); in the
        // `play` build, or without Play services, it stays local. The audio stages above and below (equalizer, volume
        // normalisation, speed and pitch, crossfade, sleep timer, call pauses) are the phone's player's; what is heard is
        // followed on the session's player, on a receiver too.
        routing = sessionRouter.getOrNull()?.route(this, player)
        val sessionPlayer = routing?.player ?: player
        // The DUB stage: on this player's audio, bypassed while the session plays on a receiver.
        dub.attach(player, scope, sessionPlayer)
        // Songs heard long enough count as played (the artists' artwork follows what is played most).
        val tracker = PlayCountTracker(SystemClock::elapsedRealtime)
        playCounts = PlayCountRecorder(sessionPlayer, tracker, scope, playCountWrites, listened = listeningHistory::record) { uri ->
            playHistory.recordPlay(uri, System.currentTimeMillis())
        }.apply { attach() }
        // Another app holding the call audio profile without taking focus (VoIP, dictation) would turn the music into
        // mono call audio: Sonora pauses meanwhile and resumes after.
        callInterruption = CallInterruptionMonitor(this, player, scope).apply { attach() }
        // The sleep timer counts here, so it keeps running with the app in the background.
        sleepTimer.attach(PlayerSleepPlayback(player), scope)
        // Songs blend into each other when the setting is on: the outgoing tail plays on a second player of its own,
        // through the same equalizer, volume normalisation and speed and pitch, while this one (the session's) moves on.
        // The tail player lives until the crossfade controller detaches, after the scope is cancelled.
        val tailPlayer = {
            SessionPlayer.build(this, replayGain = { replayGain.settings }, handleAudioFocus = false) { equalizer.settings }
                .also { tail -> followTempo(tail, tempo.tempo.lockedTo(PlaybackTempo.DEFAULT, armed), scope) }
        }
        val crossfade = CrossfadeController(player, fadeGain, tailPlayer, scope).apply { attach() }
        this.crossfade = crossfade
        // No crossfade into the next song while a loop (or the mark for one) holds this one, nor while DUB mode is on.
        scope.launch {
            crossfadeSettings.seconds
                .lockedTo(0, combine(armed, loops.holdsPlayback, dub.holdsCrossfade) { a, b, c -> a || b || c })
                .collect(crossfade::setSeconds)
        }

        val access = SafTrackAccess(this)
        val resumption = PlaybackResumption(snapshots, access::accessibleFilter, access::exists, Dispatchers.IO)
        val owner = playback.sessionOwner
        val car = CarBrowser(this, carLibrary, scope, CarArtwork(this)::carUri, access::accessibleFilter)
        val callback = SonoraLibraryCallback(owner, resumption, scope, car)
        session = SonoraSession.build(this, sessionPlayer, callback, sessionActivity())
        owner.keepConnected()
        // After process death the app opens on the last track, paused where it was.
        callback.restoreIfEmpty(sessionPlayer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // A pause for another app's call is to be undone: the service stays for it.
        if (callInterruption?.isHolding == true) return
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Before the player goes: a song heard long enough by now still counts.
        playCounts?.detach()
        playCounts = null
        sleepTimer.detach()
        scope.cancel()
        crossfade?.detach()
        crossfade = null
        callInterruption?.detach()
        callInterruption = null
        routing?.release()
        routing = null
        releaseBitPerfect?.invoke()
        releaseBitPerfect = null
        playbackClock.detach()
        loops.detach()
        dub.detach()
        // Releases the cast player too, which releases the local player.
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Sonora's small icon on its own "Playback" channel (Media3's default channel is removed once). */
    @OptIn(UnstableApi::class)
    private fun notificationProvider(): DefaultMediaNotificationProvider {
        getSystemService<NotificationManager>()?.run {
            // Media3's own channel from earlier versions; never in use here (nothing is posted before onCreate ends).
            runCatching { deleteNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) }
            createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.playback_channel_description) },
            )
        }
        return DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_stat_sonora) }
    }

    /** Tapping the notification or the system media controls opens the app on Now Playing. */
    private fun sessionActivity(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** Boolean extra on the session activity's intent: show Now Playing. */
        const val EXTRA_OPEN_NOW_PLAYING = "io.sonora.player.extra.OPEN_NOW_PLAYING"

        private const val LOG_TAG = "SonoraPlayer"
        private const val CHANNEL_ID = "playback"
    }
}

/**
 * Sonora's own controller's MediaItems lose localConfiguration over IPC; restore the URI from request metadata. Only
 * for Sonora's own controller: [SonoraSessionCallback.onAddMediaItems] never calls this for an external one.
 */
internal object UriResolvingCallback : MediaSession.Callback {
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (item.localConfiguration == null && uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList(),
        )
}
