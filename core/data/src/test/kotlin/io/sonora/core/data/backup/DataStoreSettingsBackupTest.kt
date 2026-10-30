package io.sonora.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.BackupModule
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.data.preferences.DataStoreVisualCollectionPreferencesRepository
import io.sonora.core.data.preferences.DataStoreCrossfadeRepository
import io.sonora.core.data.preferences.DataStorePlaybackTempoRepository
import io.sonora.core.data.preferences.DataStoreReplayGainRepository
import io.sonora.core.domain.backup.BackupReading
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.BackupSettingsPolicy
import io.sonora.core.domain.backup.LibraryBackup
import io.sonora.core.domain.backup.SettingValue
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.domain.playback.TempoRange
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings side of a backup against real preferences DataStore files, built as the app builds them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreSettingsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun withStores(block: suspend (DataStore<Preferences>, DataStore<Preferences>, DataStoreSettingsBackup) -> Unit) = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val settings = createSettingsDataStore(scope) { File(folder.root, "sonora_settings.preferences_pb") }
        val equalizer = createSettingsDataStore(scope) { File(folder.root, "sonora_equalizer.preferences_pb") }
        try {
            block(settings, equalizer, DataStoreSettingsBackup(mapOf("sonora_settings" to settings, "sonora_equalizer" to equalizer)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `reading names each file and keeps every value's type`() = withStores { settings, equalizer, backup ->
        settings.edit {
            it[booleanPreferencesKey("b")] = true
            it[intPreferencesKey("i")] = 3
            it[longPreferencesKey("l")] = 4L
            it[floatPreferencesKey("f")] = 0.5f
            it[doublePreferencesKey("d")] = 0.25
            it[stringPreferencesKey("s")] = "DARK"
            it[stringSetPreferencesKey("set")] = setOf("x")
        }

        assertThat(backup.read()).containsExactly(
            BackupSettingsFile(
                "sonora_settings",
                mapOf(
                    "b" to SettingValue.BooleanValue(true),
                    "i" to SettingValue.IntValue(3),
                    "l" to SettingValue.LongValue(4),
                    "f" to SettingValue.FloatValue(0.5f),
                    "d" to SettingValue.DoubleValue(0.25),
                    "s" to SettingValue.StringValue("DARK"),
                    "set" to SettingValue.StringSetValue(setOf("x")),
                ),
            ),
            BackupSettingsFile("sonora_equalizer", emptyMap()),
        )
    }

    @Test
    fun `merging writes the backed-up values and keeps keys the backup does not name`() = withStores { settings, equalizer, backup ->
        settings.edit {
            it[stringPreferencesKey("appearance.theme")] = "LIGHT"
            it[booleanPreferencesKey("now_playing.gravity")] = true
        }

        backup.merge(
            listOf(
                BackupSettingsFile("sonora_settings", mapOf("appearance.theme" to SettingValue.StringValue("DARK"))),
                BackupSettingsFile("sonora_equalizer", mapOf("equalizer.enabled" to SettingValue.BooleanValue(true))),
                BackupSettingsFile("someone_elses_file", mapOf("x" to SettingValue.StringValue("y"))),
            ),
        )

        val stored = settings.data.first()
        assertThat(stored[stringPreferencesKey("appearance.theme")]).isEqualTo("DARK")
        assertThat(stored[booleanPreferencesKey("now_playing.gravity")]).isTrue()
        assertThat(equalizer.data.first()[booleanPreferencesKey("equalizer.enabled")]).isTrue()
    }

    @Test
    fun `a value of another type than the stored one, or a secret, is not written`() = withStores { settings, _, backup ->
        settings.edit { it[booleanPreferencesKey("now_playing.gravity")] = true }

        backup.merge(
            listOf(
                BackupSettingsFile(
                    "sonora_settings",
                    mapOf(
                        "now_playing.gravity" to SettingValue.StringValue("yes"),
                        "account.session_key" to SettingValue.StringValue("s3cret"),
                    ),
                ),
            ),
        )

        val stored = settings.data.first().asMap().mapKeys { it.key.name }
        assertThat(stored).containsExactly("now_playing.gravity", true)
    }

    /** Every settings file a backup carries, by its persisted name, wired into a backup as [BackupModule] wires them. */
    private class AppStores(val files: Map<String, DataStore<Preferences>>) {
        val backup = BackupModule.provideSettingsBackup(
            settings = files.getValue("sonora_settings"),
            sort = files.getValue("sonora_sort"),
            equalizer = files.getValue("sonora_equalizer"),
            tempo = files.getValue("sonora_playback_tempo"),
            replayGain = files.getValue("sonora_replay_gain"),
            crossfade = files.getValue("sonora_crossfade"),
            visualCollection = files.getValue("sonora_cover_flow"),
        )
        val tempo get() = DataStorePlaybackTempoRepository(files.getValue("sonora_playback_tempo"))
        val replayGain get() = DataStoreReplayGainRepository(files.getValue("sonora_replay_gain"))
        val crossfade get() = DataStoreCrossfadeRepository(files.getValue("sonora_crossfade"))
        val visualCollection get() = DataStoreVisualCollectionPreferencesRepository(files.getValue("sonora_cover_flow"))
    }

    /** The app's settings files twice, in two folders: the device a backup is made on and the one it is restored to. */
    private fun withAppStores(block: suspend (source: AppStores, target: AppStores) -> Unit) = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        fun stores(device: String): AppStores {
            val dir = folder.newFolder(device)
            return AppStores(APP_FILES.associateWith { name -> createSettingsDataStore(scope) { File(dir, "$name.preferences_pb") } })
        }
        try {
            block(stores("source"), stores("target"))
        } finally {
            job.cancelAndJoin()
        }
    }

    /** Backs [source] up through the JSON file and restores it into [target], as Create and Apply Restore do. */
    private suspend fun backUpAndRestore(source: AppStores, target: AppStores) {
        val codec = BackupJson()
        val file = codec.encode(LibraryBackup(createdAtMs = 1, settings = BackupSettingsPolicy.filter(source.backup.read())))
        val read = codec.decode(file) as BackupReading.Read
        target.backup.merge(read.backup.settings)
    }

    @Test
    fun `a backup carries every settings file by its persisted name and nothing else`() = withAppStores { source, _ ->
        assertThat(source.backup.read().map { it.name }).containsExactly(
            "sonora_settings",
            "sonora_sort",
            "sonora_equalizer",
            "sonora_playback_tempo",
            "sonora_replay_gain",
            "sonora_crossfade",
            "sonora_cover_flow",
        )
    }

    @Test
    fun `speed and pitch survive a backup and restore`() = withAppStores { source, target ->
        source.tempo.update { PlaybackTempo.ofPercent(125, -2) }

        backUpAndRestore(source, target)

        assertThat(target.tempo.tempo.first()).isEqualTo(PlaybackTempo.ofPercent(125, -2))
    }

    @Test
    fun `the fine speed, Master Tempo and range travel in a backup`() = withAppStores { source, target ->
        val tempo = PlaybackTempo(speedBasisPoints = 10_123, semitones = -2, masterTempo = false, range = TempoRange.SIXTEEN)
        source.tempo.update { tempo }

        val carried = BackupSettingsPolicy.filter(source.backup.read()).single { it.name == "sonora_playback_tempo" }.values.keys
        backUpAndRestore(source, target)

        assertThat(carried).containsAtLeast("tempo.speed_basis_points", "tempo.master_tempo", "tempo.range", "tempo.speed_percent")
        assertThat(target.tempo.tempo.first()).isEqualTo(tempo)
    }

    @Test
    fun `volume normalisation survives a backup and restore`() = withAppStores { source, target ->
        source.replayGain.update { ReplayGainSettings(ReplayGainMode.ALBUM, preampDb = 3f) }

        backUpAndRestore(source, target)

        assertThat(target.replayGain.settings.first()).isEqualTo(ReplayGainSettings(ReplayGainMode.ALBUM, preampDb = 3f))
    }

    @Test
    fun `the crossfade length survives a backup and restore`() = withAppStores { source, target ->
        source.crossfade.setSeconds(6)

        backUpAndRestore(source, target)

        assertThat(target.crossfade.seconds.first()).isEqualTo(6)
    }

    @Test
    fun `the visual collection settings survive a backup and restore`() = withAppStores { source, target ->
        source.visualCollection.setHideAlbumsWithoutArtwork(true)

        backUpAndRestore(source, target)

        assertThat(target.visualCollection.hideAlbumsWithoutArtwork.first()).isTrue()
    }

    @Test
    fun `restoring a backup written before those files were carried leaves them as they are`() = withAppStores { _, target ->
        target.tempo.update { PlaybackTempo.ofPercent(90, 1) }
        target.replayGain.update { ReplayGainSettings(ReplayGainMode.TRACK, preampDb = -2f) }
        target.crossfade.setSeconds(4)
        target.visualCollection.setHideAlbumsWithoutArtwork(true)
        val older = """
            {"format":"sonora-backup","version":1,"createdAtMs":1,
             "settings":[{"name":"sonora_settings","values":[{"key":"appearance.theme","type":"string","value":"DARK"}]},
                         {"name":"sonora_sort","values":[]},{"name":"sonora_equalizer","values":[]}]}
        """.trimIndent()

        target.backup.merge((BackupJson().decode(older) as BackupReading.Read).backup.settings)

        assertThat(target.files.getValue("sonora_settings").data.first()[stringPreferencesKey("appearance.theme")]).isEqualTo("DARK")
        assertThat(target.tempo.tempo.first()).isEqualTo(PlaybackTempo.ofPercent(90, 1))
        assertThat(target.replayGain.settings.first()).isEqualTo(ReplayGainSettings(ReplayGainMode.TRACK, preampDb = -2f))
        assertThat(target.crossfade.seconds.first()).isEqualTo(4)
        assertThat(target.visualCollection.hideAlbumsWithoutArtwork.first()).isTrue()
    }

    private companion object {
        val APP_FILES = listOf(
            "sonora_settings",
            "sonora_sort",
            "sonora_equalizer",
            "sonora_playback_tempo",
            "sonora_replay_gain",
            "sonora_crossfade",
            "sonora_cover_flow",
        )
    }
}
