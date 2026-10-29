package io.sonora.core.djengine.pipeline

import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory

/**
 * Wall time per [AnalysisStage] of one run, from the injected clock, and the stage that is running now (the one a
 * failure is reported against). Only stages that were timed at least once appear in [stageMs].
 */
internal class StageTimer(private val clock: PipelineClock) {
    private val nanos = LongArray(STAGES.size)
    private val ran = BooleanArray(STAGES.size)

    /** Sum of everything added so far: lets a caller subtract the time of the stages nested inside its own. */
    var total = 0L
        private set

    var current: AnalysisStage = AnalysisStage.DECODE

    fun now(): Long = clock.monotonicNanos()

    fun add(stage: AnalysisStage, elapsedNanos: Long) {
        nanos[stage.ordinal] += elapsedNanos
        ran[stage.ordinal] = true
        total += elapsedNanos
    }

    inline fun <T> time(stage: AnalysisStage, block: () -> T): T {
        current = stage
        val start = now()
        try {
            return block()
        } finally {
            add(stage, now() - start)
        }
    }

    /** Milliseconds (rounded) of every stage that ran. */
    fun stageMs(): Map<AnalysisStage, Long> =
        STAGES.filter { ran[it.ordinal] }.associateWith { (nanos[it.ordinal] + NANOS_PER_MS / 2) / NANOS_PER_MS }

    private companion object {
        val STAGES = AnalysisStage.entries
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** One stage's sink inside a [SinkRouter]: its blocks and its [finish] are timed under [stage]. */
internal class Tap<out R>(private val stage: AnalysisStage, private val sink: MonoSink<R>, private val timer: StageTimer) {

    fun accept(samples: FloatArray, offset: Int, count: Int) {
        timer.current = stage
        val start = timer.now()
        sink.accept(samples, offset, count)
        timer.add(stage, timer.now() - start)
    }

    fun finish(): R = timer.time(stage) { sink.finish() }

    fun close() = closeQuietly(sink)
}

/**
 * Hands the decoded mono blocks to every tap: directly when the tap's factory runs at the source rate, otherwise
 * through one [Resampler] stream per distinct target rate, shared by the taps at that rate (so the mel front end at
 * 22.05 kHz and, say, an onset stage at 16 kHz get two conversions, and two stages at 22.05 kHz one). A conversion's
 * own time is charged to the stage of the first tap that asked for its rate.
 */
internal class SinkRouter(private val sourceRateHz: Int, private val resampler: Resampler, private val timer: StageTimer) {
    private val direct = ArrayList<Tap<*>>()
    private val groups = LinkedHashMap<Int, Group>()
    private val all = ArrayList<Tap<*>>()

    private inner class Group(val stage: AnalysisStage, rateHz: Int) {
        val taps = ArrayList<Tap<*>>()
        val converter: MonoSink<Unit> = resampler.into(
            object : MonoSink<Unit> {
                override fun accept(samples: FloatArray, offset: Int, count: Int) {
                    for (tap in taps) tap.accept(samples, offset, count)
                }

                override fun finish() = Unit
            },
            sourceRateHz,
            rateHz,
        )

        /** Runs [action] on the converter and charges its time, less the taps it fed, to [stage]. */
        inline fun timed(action: () -> Unit) {
            val start = timer.now()
            val nestedBefore = timer.total
            timer.current = stage
            action()
            val nested = timer.total - nestedBefore
            timer.add(stage, timer.now() - start - nested)
        }
    }

    fun <R> tap(stage: AnalysisStage, factory: MonoSinkFactory<R>): Tap<R> {
        val tap = Tap(stage, factory.create(), timer)
        all += tap
        val rate = factory.sampleRateHz
        if (rate == sourceRateHz) direct += tap else groups.getOrPut(rate) { Group(stage, rate) }.taps += tap
        return tap
    }

    fun accept(samples: FloatArray, count: Int) {
        for (tap in direct) tap.accept(samples, 0, count)
        for (group in groups.values) group.timed { group.converter.accept(samples, 0, count) }
    }

    /** Flushes the conversions, so their last samples reach the taps; call once after the last block. */
    fun flush() {
        for (group in groups.values) group.timed { group.converter.finish() }
    }

    /** Closes every sink and conversion that is [AutoCloseable], finished or not. */
    fun closeAll() {
        for (tap in all) tap.close()
        for (group in groups.values) closeQuietly(group.converter)
    }
}

/**
 * Forwards an increasing progress to the listener: never a value below the last one, steps of at least [MIN_STEP]
 * (no flood of tiny updates), and 1 exactly once, at the end.
 */
internal class ProgressMeter(private val listener: (Float) -> Unit) {
    private var last = -1f

    fun report(fraction: Float) {
        val value = fraction.coerceIn(0f, MAX_BEFORE_DONE)
        if (last < 0f || value - last >= MIN_STEP) emit(value)
    }

    fun done() {
        if (last < 1f) emit(1f)
    }

    private fun emit(value: Float) {
        last = value
        listener(value)
    }

    private companion object {
        const val MIN_STEP = 0.001f
        const val MAX_BEFORE_DONE = 0.999f
    }
}

private fun closeQuietly(target: Any) {
    if (target is AutoCloseable) runCatching { target.close() }
}
