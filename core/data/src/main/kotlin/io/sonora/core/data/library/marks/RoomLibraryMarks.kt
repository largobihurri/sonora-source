package io.sonora.core.data.library.marks

import io.sonora.core.data.library.db.FavouriteEntity
import io.sonora.core.data.library.db.HiddenItemEntity
import io.sonora.core.data.library.db.LibraryMarksDao
import io.sonora.core.data.library.db.PinEntity
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [LibraryMarksRepository] in the library database (`favourites`, `pins`, `hidden_items`). Hidden songs, albums and
 * artists drop out of the library's own queries (see `LibraryDao`); hidden playlists are filtered by their lists.
 */
@Singleton
class RoomLibraryMarks @Inject constructor(
    private val dao: LibraryMarksDao,
    private val clock: Clock,
) : LibraryMarksRepository {

    override fun favourites(kind: MarkKind): Flow<List<String>> = dao.favourites(kind.name).distinctUntilChanged()

    override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) {
        if (favourite) {
            dao.insertFavourite(FavouriteEntity(ref.kind.name, ref.key, clock.millis()))
        } else {
            dao.deleteFavourite(ref.kind.name, ref.key)
        }
    }

    override fun pins(context: PinContext): Flow<List<String>> = dao.pins(context.id).distinctUntilChanged()

    override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) {
        if (pinned) {
            dao.insertPin(PinEntity(context.id, ref.kind.name, ref.key, clock.millis()))
        } else {
            dao.deletePin(context.id, ref.kind.name, ref.key)
        }
    }

    override fun hidden(): Flow<List<HiddenItem>> = dao.hidden().map { rows -> rows.mapNotNull(HiddenItemEntity::toDomain) }

    override suspend fun hide(item: HiddenItem) = dao.insertHidden(HiddenItemEntity.of(item))

    override suspend fun restore(ref: ItemRef) = dao.deleteHidden(ref.kind.name, ref.key)

    override suspend fun forgetSongs(uris: Collection<String>) = dao.forgetSongs(uris)

    /** Play counts (`play_counts`) move with the marks, in the same transaction. */
    override suspend fun moveSongs(moves: Map<String, String>) = dao.moveSongs(moves)
}
