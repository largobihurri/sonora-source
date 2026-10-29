package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.schedule.ScheduleDecision
import io.sonora.core.djengine.schedule.ScheduleTrace
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.SchedulingInput
import org.junit.Test

class ScheduleTraceLinesTest {

    private val uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.flac"
    private val tag = ScheduleTraceLines.tag(uri)

    @Test
    fun `a uri is shown as a short stable tag, not as its path`() {
        assertThat(tag).matches("u:[0-9a-f]{8}")
        assertThat(ScheduleTraceLines.tag(uri)).isEqualTo(tag)
        assertThat(ScheduleTraceLines.tag("$uri.2")).isNotEqualTo(tag)
    }

    @Test
    fun `an input shows the playing track, the up next count and the load`() {
        val input = SchedulingInput(true, uri, listOf("b", "c", "d"), DeviceLoad())

        assertThat(ScheduleTraceLines.line(ScheduleTrace.InputChanged(input)))
            .isEqualTo("schedule input playing=$tag upNext=3 load=ok")
    }

    @Test
    fun `an input without a playing track under battery saver and heat`() {
        val input = SchedulingInput(true, null, emptyList(), DeviceLoad(batterySaver = true, thermalThrottled = true))

        assertThat(ScheduleTraceLines.line(ScheduleTrace.InputChanged(input)))
            .isEqualTo("schedule input playing=none upNext=0 load=battery_saver+thermal")
        assertThat(ScheduleTraceLines.line(ScheduleTrace.InputChanged(input.copy(load = DeviceLoad(thermalThrottled = true)))))
            .isEqualTo("schedule input playing=none upNext=0 load=thermal")
    }

    @Test
    fun `a decision shows the track, its id when known, the time since the input and its reason`() {
        val id = AudioId.of(AudioId.PAYLOAD_V1, "b82465a0264b" + "0".repeat(52))

        assertThat(ScheduleTraceLines.line(ScheduleTrace.Decision(uri, ScheduleDecision.STARTED, 214_000, id)))
            .isEqualTo("schedule started uri=$tag id=ap1:b82465a0264b after=214000ms")
        assertThat(ScheduleTraceLines.line(ScheduleTrace.Decision(uri, ScheduleDecision.QUEUED, 3, detail = ScheduleTrace.HELD_BY_LOAD)))
            .isEqualTo("schedule queued uri=$tag after=3ms reason=held_by_load")
        assertThat(ScheduleTraceLines.line(ScheduleTrace.Decision(uri, ScheduleDecision.SKIPPED_ANALYSED, 150, id)))
            .isEqualTo("schedule skipped_analysed uri=$tag id=ap1:b82465a0264b after=150ms")
    }
}
