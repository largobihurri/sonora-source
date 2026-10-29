package io.sonora.core.domain.library.duplicates

import kotlinx.coroutines.flow.first

/**
 * The background check (the daily scan and a catch-up): scans the library and, when the user allows it, posts the
 * notification as [DuplicateAlertPolicy] says. Returns `false` when the scan failed, to be retried.
 */
class CheckDuplicates(private val scan: DuplicateScan, private val alerts: DuplicateAlerts, private val notifier: DuplicateNotifier) {
    suspend operator fun invoke(): Boolean {
        val groups = scan.scanNow()?.size ?: return false
        val last = alerts.lastNotifiedCount()
        val posted = DuplicateAlertPolicy.shouldNotify(groups, last, alerts.enabled.first()) && notifier.notify(groups)
        val next = DuplicateAlertPolicy.lastNotifiedAfter(groups, last, posted)
        if (next != last) alerts.setLastNotifiedCount(next)
        return true
    }
}
