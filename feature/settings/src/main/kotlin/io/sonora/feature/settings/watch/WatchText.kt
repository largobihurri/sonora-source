package io.sonora.feature.settings.watch

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.feature.settings.R
import java.text.NumberFormat

/** The words of Settings › Watch, built from the copy in [Strings] so they are tested on the JVM. */
internal object WatchText {

    private const val SEPARATOR = " · "

    fun size(bytes: Long, strings: Strings): String = LocaleFormats.fileSize(bytes.coerceAtLeast(0), strings)

    /** "14.0 GiB used of 23.0 GiB". */
    fun spaceCaption(space: WatchSpace, strings: Strings): String =
        strings.get(R.string.watch_space_caption, size(space.usedBytes, strings), size(space.totalBytes, strings))

    /** "3 songs · 340.0 MiB", or "No songs". */
    fun songsValue(space: WatchSpace, strings: Strings): String =
        if (space.songCount == 0) {
            strings.get(R.string.watch_no_songs)
        } else {
            songCount(space.songCount, strings) + SEPARATOR + size(space.songsBytes, strings)
        }

    /** A fraction as the language writes a percentage: "42%". */
    fun percent(fraction: Float, strings: Strings): String =
        NumberFormat.getPercentInstance(strings.locale).format(fraction.coerceIn(0f, 1f).toDouble())

    // The send in progress

    fun connecting(strings: Strings): String = strings.get(R.string.watch_connecting)

    /** "Song 2 of 8". */
    fun songPosition(song: CurrentSong, strings: Strings): String =
        strings.get(R.string.watch_song_position, song.number, song.count)

    fun phase(song: CurrentSong, strings: Strings): String = strings.get(
        when (song.phase) {
            SendProgress.Phase.ENCODING -> R.string.watch_phase_encoding
            SendProgress.Phase.SENDING -> R.string.watch_phase_sending
        },
    )

    /** "Overall: 25%". */
    fun overall(fraction: Float, strings: Strings): String = strings.get(R.string.watch_overall, percent(fraction, strings))

    // The last result

    /** What the last send did, one line per outcome that happened. */
    fun doneLines(done: WatchTransfer.Done, strings: Strings): List<String> {
        val lines = buildList {
            if (done.sentCount > 0) add(strings.plural(R.plurals.watch_done_sent, done.sentCount, done.sentCount))
            if (done.alreadyThereCount > 0) {
                add(strings.plural(R.plurals.watch_done_already_there, done.alreadyThereCount, done.alreadyThereCount))
            }
            if (done.failed.isNotEmpty()) add(strings.plural(R.plurals.watch_done_failed, done.failed.size, done.failed.size))
        }
        return lines.ifEmpty { listOf(strings.get(R.string.watch_done_nothing)) }
    }

    /** "10.0 MiB at 2.0 MiB/s"; `null` when nothing travelled or the speed is unknown. */
    fun speedLine(done: WatchTransfer.Done, strings: Strings): String? {
        val speed = done.bytesPerSecond ?: return null
        if (done.transferredBytes <= 0) return null
        return strings.get(R.string.watch_done_speed, size(done.transferredBytes, strings), size(speed, strings))
    }

    fun failure(failure: WatchSyncFailure, strings: Strings): String = when (failure) {
        WatchSyncFailure.NoWatch -> strings.get(R.string.watch_failure_no_watch)
        WatchSyncFailure.WatchAppIncompatible -> strings.get(R.string.watch_failure_incompatible)
        is WatchSyncFailure.NotEnoughSpace ->
            strings.get(R.string.watch_failure_no_space, size(failure.neededBytes, strings), size(failure.availableBytes, strings))
        WatchSyncFailure.ConnectionLost -> strings.get(R.string.watch_failure_connection_lost)
        WatchSyncFailure.NothingToSend -> strings.get(R.string.watch_failure_nothing)
        is WatchSyncFailure.Unexpected -> strings.get(R.string.watch_failure_unexpected)
    }

    /** "Bad Song — couldn’t convert it". */
    fun failedSong(song: FailedSong, strings: Strings): String = strings.get(
        R.string.watch_failed_song,
        song.title,
        strings.get(
            when (song.reason) {
                SyncError.NO_SPACE -> R.string.watch_reason_no_space
                SyncError.INVALID_TRACK -> R.string.watch_reason_invalid
                SyncError.CHECKSUM_MISMATCH -> R.string.watch_reason_damaged
                SyncError.STORAGE_ERROR -> R.string.watch_reason_storage
                SyncError.ENCODING_FAILED -> R.string.watch_reason_encoding
            },
        ),
    )

    // The connection

    fun link(link: WatchLink, strings: Strings): String = strings.get(
        when (link) {
            WatchLink.CHECKING -> R.string.watch_link_checking
            WatchLink.CONNECTED -> R.string.watch_link_connected
            WatchLink.SENDING -> R.string.watch_link_sending
            WatchLink.NO_WATCH -> R.string.watch_link_no_watch
            WatchLink.APP_OUTDATED -> R.string.watch_link_outdated
            WatchLink.UNREACHABLE -> R.string.watch_link_unreachable
        },
    )

    /** What to do about a connection that is not working; `null` when there is nothing to do. */
    fun linkAdvice(link: WatchLink, strings: Strings): String? = when (link) {
        WatchLink.NO_WATCH -> strings.get(R.string.watch_advice_no_watch)
        WatchLink.APP_OUTDATED -> strings.get(R.string.watch_advice_outdated)
        WatchLink.UNREACHABLE -> strings.get(R.string.watch_advice_unreachable)
        WatchLink.CHECKING, WatchLink.CONNECTED, WatchLink.SENDING -> null
    }

    // The songs on the watch

    fun groupTitle(group: WatchAlbumGroup, strings: Strings): String = group.album ?: strings.get(R.string.watch_no_album)

    /** "Artist · 2 songs · 2.0 KiB"; "Various Artists" when the songs disagree, no artist when none is known. */
    fun groupSubtitle(group: WatchAlbumGroup, strings: Strings): String {
        val artist = when {
            group.variousArtists -> strings.get(R.string.watch_various_artists)
            else -> group.artist
        }
        return listOfNotNull(artist, songCount(group.songs.size, strings), size(group.sizeBytes, strings)).joinToString(SEPARATOR)
    }

    /** "3:20 · 3.0 MiB". */
    fun songSubtitle(track: ManifestTrack, strings: Strings): String =
        LocaleFormats.duration(track.durationMs) + SEPARATOR + size(track.sizeBytes, strings)

    fun removeTitle(count: Int, strings: Strings): String = strings.plural(R.plurals.watch_remove_title, count, count)

    fun message(message: WatchMessage, strings: Strings): String = when (message) {
        is WatchMessage.Removed -> strings.plural(R.plurals.watch_removed, message.count, message.count)
        is WatchMessage.PartlyRemoved -> strings.get(R.string.watch_removed_partly, message.removed, message.requested)
        WatchMessage.RemoveFailed -> strings.get(R.string.watch_remove_failed)
    }

    private fun songCount(count: Int, strings: Strings): String = strings.plural(R.plurals.watch_song_count, count, count)
}
