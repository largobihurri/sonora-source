package io.sonora.feature.library.ui

import io.sonora.core.designsystem.text.Strings
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.feature.library.R

/** The banners "Send to Watch" shows: that it started, that one is already running, and how the send ended. */
internal object WatchNotice {

    /** "Sending “Bandido” to your watch". */
    fun started(name: String, strings: Strings): String = strings.get(R.string.notice_watch_started, name)

    fun busy(strings: Strings): String = strings.get(R.string.notice_watch_busy)

    /** How a finished or failed send went; `null` for a state that is not an ending. */
    fun ended(state: WatchSyncState, strings: Strings): String? = when (state) {
        WatchSyncState.Idle, is WatchSyncState.Running -> null
        is WatchSyncState.Finished -> {
            val sent = state.report.sent.size
            val failed = state.report.failed.size
            when {
                failed > 0 -> strings.get(R.string.notice_watch_partly, sent, failed)
                sent > 0 -> strings.plural(R.plurals.notice_watch_sent, sent, sent)
                else -> strings.get(R.string.notice_watch_already)
            }
        }
        is WatchSyncState.Failed -> strings.get(
            when (state.failure) {
                WatchSyncFailure.NoWatch -> R.string.notice_watch_no_watch
                WatchSyncFailure.WatchAppIncompatible -> R.string.notice_watch_incompatible
                is WatchSyncFailure.NotEnoughSpace -> R.string.notice_watch_no_space
                WatchSyncFailure.ConnectionLost -> R.string.notice_watch_connection_lost
                WatchSyncFailure.NothingToSend -> R.string.notice_watch_nothing
                is WatchSyncFailure.Unexpected -> R.string.notice_watch_failed
            },
        )
    }
}
