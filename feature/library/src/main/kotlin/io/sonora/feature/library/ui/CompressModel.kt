package io.sonora.feature.library.ui

import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.PasswordCheck
import io.sonora.core.domain.archive.PasswordStrength
import io.sonora.feature.library.CompressTarget
import io.sonora.feature.library.PlaylistSource

/** What "Compress" packs for [this] menu target; `null` for artists and folders, which have no Compress. */
internal fun MenuTarget.compressTarget(): CompressTarget? = when (this) {
    is MenuTarget.Song -> {
        val name = listOf(track.artist.trim(), track.title.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
        CompressTarget(ArchiveKind.SONG, name, PlaylistSource.Tracks(listOf(track)))
    }
    is MenuTarget.AlbumItem -> CompressTarget(ArchiveKind.ALBUM, album.title, PlaylistSource.Album(album.id))
    is MenuTarget.PlaylistItem -> CompressTarget(ArchiveKind.PLAYLIST, playlist.playlist.name, PlaylistSource.Tracks(playlist.playableTracks))
    is MenuTarget.ArtistItem, is MenuTarget.FolderItem -> null
}

/** The line under the password field: nothing typed, strong, or why it is weak ([PasswordStrength]). */
internal enum class PasswordHint { NONE, STRONG, TOO_SHORT, TOO_FEW_KINDS }

internal fun passwordHint(password: CharSequence): PasswordHint = when {
    password.isEmpty() -> PasswordHint.NONE
    else -> when (PasswordStrength.check(password)) {
        PasswordCheck.Strong -> PasswordHint.STRONG
        is PasswordCheck.TooShort -> PasswordHint.TOO_SHORT
        is PasswordCheck.TooFewKinds -> PasswordHint.TOO_FEW_KINDS
    }
}
