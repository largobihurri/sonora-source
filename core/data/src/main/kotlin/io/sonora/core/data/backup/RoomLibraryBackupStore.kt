package io.sonora.core.data.backup

import io.sonora.core.data.backup.db.BackupDao
import io.sonora.core.data.library.db.FavouriteEntity
import io.sonora.core.data.library.db.HiddenItemEntity
import io.sonora.core.data.library.db.PinEntity
import io.sonora.core.data.library.db.PlayCountEntity
import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.RestoredMarks
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.port.LibraryBackupStore
import javax.inject.Inject

/** [LibraryBackupStore] over the library database (`tracks`, `favourites`, `pins`, `hidden_items`, `play_counts`). */
class RoomLibraryBackupStore @Inject constructor(private val dao: BackupDao) : LibraryBackupStore {
    override suspend fun librarySongs(): List<SongIdentity> =
        dao.librarySongs().map { SongIdentity(it.key, it.title, it.artist, it.album, it.durationMs) }

    override suspend fun favourites(): List<BackupFavourite> =
        dao.favourites().mapNotNull { row -> ref(row.kind, row.key)?.let { BackupFavourite(it, row.addedAtMs) } }

    override suspend fun pins(): List<BackupPin> =
        dao.pins().mapNotNull { row -> ref(row.kind, row.key)?.let { BackupPin(PinContext(row.context), it, row.pinnedAtMs) } }

    override suspend fun hidden(): List<HiddenItem> = dao.hidden().mapNotNull(HiddenItemEntity::toDomain)

    override suspend fun plays(): List<BackupPlays> = dao.plays().map { BackupPlays(it.trackUri, it.playCount, it.lastPlayedMs) }

    override suspend fun mergeMarks(marks: RestoredMarks) = dao.mergeMarks(
        favourites = marks.favourites.map { FavouriteEntity(it.ref.kind.name, it.ref.key, it.addedAtMs) },
        pins = marks.pins.map { PinEntity(it.context.id, it.ref.kind.name, it.ref.key, it.pinnedAtMs) },
        hidden = marks.hidden.map(HiddenItemEntity::of),
    )

    override suspend fun mergePlays(plays: List<BackupPlays>) =
        dao.mergePlays(plays.map { PlayCountEntity(it.key, it.count, it.lastPlayedMs) })

    /** `null` for a kind this version does not know (written by a newer one). */
    private fun ref(kind: String, key: String): ItemRef? = MarkKind.entries.firstOrNull { it.name == kind }?.let { ItemRef(it, key) }
}
