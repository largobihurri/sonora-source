package io.sonora.core.djstore.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The written migrations of [DjStoreDatabase]; each is additive and has a test that opens the committed older schema. */
object DjStoreMigrations {

    /** v1 to v2 (D3): the `grid_edits` table. Nothing of an existing table changes. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `grid_edits` (`audio_id` TEXT NOT NULL, `bpm_hundredths` INTEGER NOT NULL, " +
                    "`anchor_ms` REAL NOT NULL, `updated_at_ms` INTEGER NOT NULL, `record_version` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`audio_id`))",
            )
        }
    }

    /** v2 to v3 (D4): the `cues` table. Nothing of an existing table changes. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cues` (`audio_id` TEXT NOT NULL, `cue_id` TEXT NOT NULL, `kind` INTEGER NOT NULL, " +
                    "`slot` INTEGER, `position_ms` REAL NOT NULL, `loop_end_ms` REAL, `color_index` INTEGER NOT NULL, " +
                    "`color_rgb` INTEGER NOT NULL, `name` TEXT NOT NULL, `updated_at_ms` INTEGER NOT NULL, " +
                    "`record_version` INTEGER NOT NULL, PRIMARY KEY(`audio_id`, `cue_id`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
