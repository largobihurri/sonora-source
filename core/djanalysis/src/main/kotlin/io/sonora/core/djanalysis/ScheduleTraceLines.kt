package io.sonora.core.djanalysis

import io.sonora.core.djengine.schedule.ScheduleTrace
import io.sonora.core.djmodel.DeviceLoad
import java.util.Locale

/**
 * The scheduler's log lines (tag `SonoraDjAnalysis`, only while DJ mode is on), so a device run can measure how long a
 * queue change takes to become work:
 * - `schedule input playing=u:1a2b3c4d upNext=3 load=ok|battery_saver|thermal|battery_saver+thermal`
 * - `schedule <decision> uri=u:1a2b3c4d [id=ap1:<12 hex>] after=<ms since that input>ms [reason=<why>]`, where the
 *   decision is `queued`, `identifying`, `skipped_analysed`, `identify_failed`, `started`, `cancelled` or `failed`.
 *
 * A track is shown by [tag], a short hash of its uri (the uri holds the file's path); `id` matches the `analysed id=`
 * line of [DjAnalysisReport].
 */
object ScheduleTraceLines {

    /** `u:` and eight hex digits of the uri's hash: stable for a uri, never its path. */
    fun tag(uri: String): String = "u:" + String.format(Locale.ROOT, "%08x", uri.hashCode())

    fun line(trace: ScheduleTrace): String = when (trace) {
        is ScheduleTrace.InputChanged -> buildString {
            val input = trace.input
            append("schedule input playing=").append(input.playingUri?.let(::tag) ?: "none")
            append(" upNext=").append(input.upNextUris.size)
            append(" load=").append(load(input.load))
        }
        is ScheduleTrace.Decision -> buildString {
            append("schedule ").append(trace.decision.name.lowercase(Locale.ROOT))
            append(" uri=").append(tag(trace.uri))
            trace.audioId?.let { append(" id=").append(it.scheme).append(':').append(it.hash.take(ID_DIGITS)) }
            append(" after=").append(trace.elapsedMs).append("ms")
            trace.detail?.let { append(" reason=").append(it) }
        }
    }

    private fun load(load: DeviceLoad): String = when {
        load.batterySaver && load.thermalThrottled -> "battery_saver+thermal"
        load.batterySaver -> "battery_saver"
        load.thermalThrottled -> "thermal"
        else -> "ok"
    }

    private const val ID_DIGITS = 12
}
