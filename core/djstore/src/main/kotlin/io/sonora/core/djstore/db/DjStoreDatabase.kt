package io.sonora.core.djstore.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The permanent DJ store (`sonora-dj.db`): the analyses of tracks, keyed by audio identity, and the memo of which file
 * holds which audio. Unlike the analysis cache this is user data: an analyser upgrade never discards a row, and the
 * database is never dropped or recreated by a schema change.
 *
 * ## Schema evolution
 *
 * Only additive migrations, each with a migration test that opens the committed schema of the previous version,
 * migrates, and checks that every old row survives byte for byte. The database is built by [builder] without any
 * destructive fallback, so a schema this build cannot reach by a written [androidx.room.migration.Migration] (a missing
 * step, or a downgrade after a newer build) fails loudly and leaves the file untouched instead of wiping it.
 *
 * Reserved versions:
 * - v1: analyses (grid, key, waveform, provenance) and the uri to audio-id memo.
 * - v2 (D3): the `grid_edits` table, the user's edits of the grid kept apart from the analysed grid (the
 *   analysis columns stay as they are, an edit never rewrites the analysis); see [DjStoreMigrations.MIGRATION_1_2].
 * - v3 (this one, D4): the `cues` table, the memory cues, hot cues and saved loops of a track, one row per cue keyed
 *   by audio id and cue id; see [DjStoreMigrations.MIGRATION_2_3].
 *
 * `audio_ids` only holds what can be recomputed by hashing the files again, so it must stay out of the app backup
 * (the app's backup rules exclude this database file; the analyses travel in the backup record instead).
 */
@Database(entities = [DjAnalysisEntity::class, AudioIdEntity::class, GridEditEntity::class, CueEntity::class], version = 3, exportSchema = true)
abstract class DjStoreDatabase : RoomDatabase() {
    abstract fun analysisDao(): DjAnalysisDao
    abstract fun audioIdDao(): AudioIdDao
    abstract fun gridEditDao(): GridEditDao
    abstract fun cueDao(): CueDao

    companion object {
        const val NAME = "sonora-dj.db"

        /**
         * The one way to open the store on disk. No destructive migration is ever configured here: a schema that cannot
         * be migrated must fail, not erase the user's analyses.
         */
        fun builder(context: Context): Builder<DjStoreDatabase> =
            Room.databaseBuilder(context, DjStoreDatabase::class.java, NAME).addMigrations(*DjStoreMigrations.ALL)
    }
}
