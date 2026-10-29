package io.sonora.core.djengine.schedule

import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.SchedulingInput
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Decides which tracks the DJ analysis works on and in what order. Pure scheduling: what an analysis is, where its
 * result is stored and how a track is identified are injected, so the queue runs on the JVM under virtual time.
 *
 * Product rules (see `odd/tasks/dj-mode.md`):
 * - Analysis happens only while [SchedulingInput.djModeOn]. The window is the playing track first, then the next
 *   [upNextLimit] distinct tracks of the queue, in queue order. Nothing outside the window is ever analysed, so the
 *   library is never analysed as a whole.
 * - One analysis runs at a time (a single worker). Duplicates are analysed once, by uri and by audio identity.
 * - The playing track pre-empts background work: when a pending playing track is not the one running, the running
 *   analysis is cancelled cooperatively and goes back to [AnalysisStatus.Queued]; it restarts when the playing
 *   track is done (analyses are not resumable, so a restart begins again; the identity is remembered). A playing
 *   track not seen before pre-empts too, because only identifying it tells whether it is already analysed.
 *   Queue reordering never pre-empts: only the playing track does.
 * - A running analysis whose track left the window (queue edit, track change) is cancelled and forgotten.
 * - Turning DJ mode off cancels the running analysis and forgets every queued and running entry. Finished entries
 *   ([AnalysisStatus.Analysed], [AnalysisStatus.Failed]) are kept.
 * - Background items (everything but the playing track) are paused while [SchedulingInput.load] disallows them
 *   (battery saver or a severe thermal state): a running one is cancelled and queued again, and they restart when
 *   the load allows. The playing track always runs.
 * - A result is saved permanently by [analyze]'s caller and never redone: [isAnalysed] is asked after [identify]
 *   and an analysed track is reported [AnalysisStatus.Analysed] without calling [analyze].
 * - Failures are not retried in a loop. One failure is remembered per audio identity for the session (a failure of
 *   [identify] per uri), reported as [AnalysisStatus.Failed] and not retried, whatever else the input does. It is
 *   cleared, so the track gets one new attempt, only when that track becomes the playing one again: the playing
 *   uri changes to it, or DJ mode is turned back on while it is playing.
 *
 * - Two requests act on a track of the window (anything else is ignored): [retry] gives a failed track one new attempt now
 *   (the same analysis a first run does: it stores its result only where there is none), and [reanalyse] runs the analysis
 *   again even though the track is analysed or failed, through [reanalyze], which replaces the stored result. A
 *   re-analysis is queued like any other work (the playing track first, so it pre-empts a running background analysis),
 *   reports [AnalysisStatus.Analysing.replacing], keeps its mark through a pre-emption or a pause for the device load,
 *   and runs once: whatever it ends in, later inputs do not repeat it.
 *
 * [status] is keyed by uri; a uri without an entry is not analysed and not scheduled. Identities are remembered for
 * the session, so a file replaced under the same uri is not re-identified until the process restarts.
 *
 * All the scheduling state lives in one coordinator coroutine on [scope], fed by an unbounded event channel (the
 * input flow, and the worker's identity, progress and end reports), so it needs no locks and behaves the same on
 * any dispatcher. The worker is a separate job on [scope]; it is awaited after a cancel, never replaced, so there
 * is never more than one analysis alive even when [analyze] is slow to notice its cancellation.
 *
 * @param identify the audio identity of a uri (suspend: it may hash the file). Throws when the file is unreadable.
 * @param isAnalysed whether a permanent analysis already exists for an identity.
 * @param analyze analyses and stores one track, reporting its stage and fraction done (0..1); it must cooperate
 *   with cancellation. Throws when the audio cannot be analysed.
 * @param reanalyze analyses one track again and replaces its stored result (on request, see [reanalyse]); defaults to
 *   [analyze], which keeps an existing result.
 * @param scope owns the coordinator and the worker; cancelling it stops the queue.
 * @param upNextLimit how many queue entries after the playing track are looked at; must not be negative.
 * @param trace told every input and every decision ([ScheduleTrace]) while DJ mode is on, nothing while it is off (not
 *   even the cancellation that turning it off causes). Called from the coordinator and from the worker, never at once
 *   from both for one track; it must be cheap and must not throw.
 * @param monotonicNanos the clock of [ScheduleTrace.Decision.elapsedMs], counted from the latest input.
 */
class DjAnalysisQueue(
    input: Flow<SchedulingInput>,
    private val identify: suspend (uri: String) -> AudioId,
    private val isAnalysed: suspend (AudioId) -> Boolean,
    private val analyze: suspend (uri: String, audioId: AudioId, onProgress: (AnalysisStage, Float) -> Unit) -> Unit,
    private val reanalyze: suspend (uri: String, audioId: AudioId, onProgress: (AnalysisStage, Float) -> Unit) -> Unit = analyze,
    private val scope: CoroutineScope,
    private val upNextLimit: Int = DEFAULT_UP_NEXT_LIMIT,
    private val trace: (ScheduleTrace) -> Unit = {},
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    private val published = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())

    /** The analysis state per uri, as the DJ screen shows it. */
    val status: StateFlow<Map<String, AnalysisStatus>> = published

    private val events = Channel<Event>(Channel.UNLIMITED)

    // Coordinator-confined state: touched only from handle().
    private var latest: SchedulingInput? = null
    private var running: Run? = null
    private var runCounter = 0L
    private val states = LinkedHashMap<String, AnalysisStatus>()
    private val identities = HashMap<String, AudioId>()

    // Written by the coordinator, read by the worker when it traces.
    @Volatile private var tracing = false

    @Volatile private var inputAtNanos = 0L

    // Written by the coordinator, read by the worker after it identifies a track.
    private val analysedIds: MutableSet<AudioId> = ConcurrentHashMap.newKeySet()
    private val failedIds = ConcurrentHashMap<AudioId, AnalysisStatus.Failed>()

    // Coordinator-confined: the uris whose next run replaces the stored analysis (asked for, not yet finished).
    private val replacing = HashSet<String>()

    init {
        require(upNextLimit >= 0) { "upNextLimit must not be negative: $upNextLimit" }
        scope.launch { for (event in events) handle(event) }
        scope.launch { input.collect { events.trySend(Event.Input(it)) } }
    }

    /**
     * Gives [uri], a failed track of the window, one new attempt now (a plain analysis: it never replaces a stored result).
     * Nothing happens for a track that has not failed or is not in the window.
     */
    fun retry(uri: String) {
        events.trySend(Event.Retry(uri))
    }

    /**
     * Runs the analysis of [uri], a track of the window, again and replaces its stored result, even when it is analysed or
     * failed; a first analysis that is running is restarted as a replacing one. Nothing happens for a track outside the
     * window or while DJ mode is off.
     */
    fun reanalyse(uri: String) {
        events.trySend(Event.Reanalyse(uri))
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Retry -> onRetry(event.uri)
            is Event.Reanalyse -> onReanalyse(event.uri)
            is Event.Input -> onInput(event.value)
            is Event.Identified -> identities[event.uri] = event.id
            is Event.Progress -> onProgress(event)
            is Event.Ended -> onEnded(event)
        }
    }

    private fun onInput(value: SchedulingInput) {
        val previous = latest
        latest = value
        tracing = value.djModeOn
        inputAtNanos = monotonicNanos()
        if (tracing) trace(ScheduleTrace.InputChanged(value))
        val playing = value.playingUri
        if (value.djModeOn && playing != null && (previous == null || !previous.djModeOn || previous.playingUri != playing)) {
            allowRetry(playing)
        }
        reconcile()
    }

    /** The playing track gets one new attempt after a failure. */
    private fun allowRetry(uri: String) {
        if (states[uri] !is AnalysisStatus.Failed) return
        states.remove(uri)
        identities[uri]?.let { failedIds.remove(it) }
    }

    private fun onRetry(uri: String) {
        val input = latest ?: return
        if (uri !in window(input) || states[uri] !is AnalysisStatus.Failed) return
        allowRetry(uri)
        decide(uri, ScheduleDecision.QUEUED, identities[uri], ScheduleTrace.RETRY)
        reconcile()
    }

    private fun onReanalyse(uri: String) {
        val input = latest ?: return
        if (uri !in window(input)) return
        val run = running
        if (run != null && run.uri == uri && !run.cancelRequested && run.replacing) return
        replacing += uri
        identities[uri]?.let { id ->
            analysedIds.remove(id)
            failedIds.remove(id)
        }
        decide(uri, ScheduleDecision.QUEUED, identities[uri], ScheduleTrace.REANALYSE)
        if (run != null && run.uri == uri && !run.cancelRequested) {
            // A first analysis is running (or deciding that the track is analysed): start again as a replacing one.
            cancel(run, ScheduleTrace.REANALYSE)
        } else {
            states[uri] = AnalysisStatus.Queued
        }
        reconcile()
    }

    private fun onProgress(event: Event.Progress) {
        val run = running ?: return
        if (run.id != event.runId || run.cancelRequested) return
        val fraction = if (event.fraction.isNaN()) 0f else event.fraction.coerceIn(0f, 1f)
        states[run.uri] = AnalysisStatus.Analysing(event.stage, fraction, run.replacing)
        publish()
    }

    private fun onEnded(event: Event.Ended) {
        val run = running
        if (run == null || run.id != event.runId) return
        running = null
        // A run that was cancelled on purpose (pre-empted, paused, restarted) leaves its mark for the next one.
        if (!run.cancelRequested) replacing.remove(run.uri)
        when (val outcome = event.outcome) {
            is Outcome.Analysed -> {
                identities[run.uri] = outcome.id
                failedIds.remove(outcome.id)
                analysedIds += outcome.id
                states[run.uri] = AnalysisStatus.Analysed
            }
            is Outcome.Failed ->
                // A failure that raced a cancellation request is the cancellation's side effect, not the track's.
                if (!run.cancelRequested) fail(run.uri, outcome.id, outcome.failure)
            Outcome.Cancelled ->
                if (!run.cancelRequested) fail(run.uri, null, AnalysisStatus.Failed("The analysis was cancelled"))
        }
        reconcile()
    }

    private fun fail(uri: String, id: AudioId?, failure: AnalysisStatus.Failed) {
        if (id != null) {
            identities[uri] = id
            failedIds[id] = failure
        }
        states[uri] = failure
    }

    private fun reconcile() {
        val input = latest ?: return
        val window = window(input)
        replacing.retainAll(window.toSet())
        admit(window, input)
        val backgroundAllowed = input.load.backgroundAllowed
        val wanted = window.filter { isPending(it) && (it == input.playingUri || backgroundAllowed) }
        val target = wanted.firstOrNull()

        val run = running
        if (run != null && !run.cancelRequested) {
            val preempted = target != null && target == input.playingUri && run.uri != target
            when {
                run.uri !in window -> cancel(run, ScheduleTrace.LEFT_WINDOW)
                run.uri !in wanted -> cancel(run, ScheduleTrace.LOAD)
                preempted -> cancel(run, ScheduleTrace.PREEMPTED)
            }
        }
        evict(window.toSet())
        publish()

        if (running == null && target != null) {
            start(target)
            publish()
        }
    }

    /** The playing track, then the next [upNextLimit] distinct queue entries; empty while DJ mode is off. */
    private fun window(input: SchedulingInput): List<String> {
        if (!input.djModeOn) return emptyList()
        val window = LinkedHashSet<String>()
        input.playingUri?.takeIf { it.isNotEmpty() }?.let(window::add)
        var budget = upNextLimit
        for (uri in input.upNextUris) {
            if (budget == 0) break
            if (uri.isNotEmpty() && window.add(uri)) budget--
        }
        return window.toList()
    }

    /** Every window uri gets an entry: [AnalysisStatus.Queued], or what its remembered identity already settled. */
    private fun admit(window: List<String>, input: SchedulingInput) {
        for (uri in window) {
            val current = states[uri]
            if (current != null && current !is AnalysisStatus.Queued && current !is AnalysisStatus.NotAnalysed) continue
            val id = identities[uri]
            val failure = id?.let { failedIds[it] }
            val next = when {
                uri in replacing -> AnalysisStatus.Queued
                id != null && id in analysedIds -> AnalysisStatus.Analysed
                failure != null -> failure
                else -> AnalysisStatus.Queued
            }
            states[uri] = next
            if (current is AnalysisStatus.Queued) continue
            when (next) {
                AnalysisStatus.Analysed -> decide(uri, ScheduleDecision.SKIPPED_ANALYSED, id)
                AnalysisStatus.Queued -> {
                    val held = uri != input.playingUri && !input.load.backgroundAllowed
                    decide(uri, ScheduleDecision.QUEUED, detail = if (held) ScheduleTrace.HELD_BY_LOAD else null)
                }
                else -> Unit
            }
        }
    }

    /** Forgets the entries that are neither finished nor in the window any more. */
    private fun evict(window: Set<String>) {
        states.entries.removeAll { (uri, state) ->
            uri !in window && (state is AnalysisStatus.Queued || state is AnalysisStatus.Analysing || state is AnalysisStatus.NotAnalysed)
        }
    }

    private fun isPending(uri: String): Boolean {
        val state = states[uri]
        return state !is AnalysisStatus.Analysed && state !is AnalysisStatus.Failed
    }

    private fun cancel(run: Run, reason: String) {
        decide(run.uri, ScheduleDecision.CANCELLED, identities[run.uri], reason)
        run.cancelRequested = true
        run.job.cancel()
        states[run.uri] = AnalysisStatus.Queued
    }

    private fun start(uri: String) {
        val runId = ++runCounter
        val knownId = identities[uri]
        val replace = uri in replacing
        states[uri] = AnalysisStatus.Analysing(AnalysisStage.IDENTIFY, 0f, replace)
        running = Run(runId, uri, scope.launch { work(runId, uri, knownId, replace) }, replacing = replace)
    }

    /** Traces a decision about [uri] while DJ mode is on (from the coordinator or the worker). */
    private fun decide(uri: String, decision: ScheduleDecision, id: AudioId? = null, detail: String? = null) {
        if (!tracing) return
        val elapsedMs = (monotonicNanos() - inputAtNanos).coerceAtLeast(0L) / NANOS_PER_MS
        trace(ScheduleTrace.Decision(uri, decision, elapsedMs, id, detail))
    }

    private fun publish() {
        published.value = LinkedHashMap(states)
    }

    private suspend fun work(runId: Long, uri: String, knownId: AudioId?, replace: Boolean) {
        var outcome: Outcome = Outcome.Cancelled
        var id = knownId
        try {
            if (knownId == null) decide(uri, ScheduleDecision.IDENTIFYING)
            id = knownId ?: identify(uri).also { events.trySend(Event.Identified(uri, it)) }
            val failure = failedIds[id]
            outcome = when {
                replace -> {
                    decide(uri, ScheduleDecision.STARTED, id, ScheduleTrace.REANALYSE)
                    reanalyze(uri, id) { stage, fraction -> events.trySend(Event.Progress(runId, stage, fraction)) }
                    Outcome.Analysed(id)
                }
                failure != null && id !in analysedIds -> Outcome.Failed(id, failure)
                id in analysedIds || isAnalysed(id) -> {
                    decide(uri, ScheduleDecision.SKIPPED_ANALYSED, id)
                    Outcome.Analysed(id)
                }
                else -> {
                    decide(uri, ScheduleDecision.STARTED, id)
                    analyze(uri, id) { stage, fraction -> events.trySend(Event.Progress(runId, stage, fraction)) }
                    Outcome.Analysed(id)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.java.simpleName
            decide(uri, if (id == null) ScheduleDecision.IDENTIFY_FAILED else ScheduleDecision.FAILED, id, reason)
            outcome = Outcome.Failed(id, AnalysisStatus.Failed(reason, AnalysisFailureKind.of(failure)))
        } finally {
            events.trySend(Event.Ended(runId, outcome))
        }
    }

    private class Run(val id: Long, val uri: String, val job: Job, val replacing: Boolean, var cancelRequested: Boolean = false)

    private sealed interface Outcome {
        /** Analysed now, or found already analysed. */
        data class Analysed(val id: AudioId) : Outcome
        data class Failed(val id: AudioId?, val failure: AnalysisStatus.Failed) : Outcome
        data object Cancelled : Outcome
    }

    private sealed interface Event {
        data class Retry(val uri: String) : Event
        data class Reanalyse(val uri: String) : Event
        data class Input(val value: SchedulingInput) : Event
        data class Identified(val uri: String, val id: AudioId) : Event
        data class Progress(val runId: Long, val stage: AnalysisStage, val fraction: Float) : Event
        data class Ended(val runId: Long, val outcome: Outcome) : Event
    }

    companion object {
        /** Tracks looked at after the playing one: enough for the next two or three mixes, never the whole queue. */
        const val DEFAULT_UP_NEXT_LIMIT: Int = 3

        private const val NANOS_PER_MS = 1_000_000L
    }
}
