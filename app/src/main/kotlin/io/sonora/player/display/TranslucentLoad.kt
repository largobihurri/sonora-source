package io.sonora.player.display

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.sonora.core.designsystem.translucent.TranslucentLoadPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier

/**
 * The richest translucency this device's GPU has kept up with (see [TranslucentLoadPolicy]), kept across launches so a
 * phone that cannot draw full translucency does not stall again on every start. It is device state, not a setting: the
 * file is left out of backups and device transfers (`backup_rules.xml`, `data_extraction_rules.xml`), so a fast phone
 * restored from a slow one starts with the full translucency. Turning the Translucency switch back on clears it
 * ([clear]).
 */
internal class TranslucentCeiling(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The ceiling for [io.sonora.core.designsystem.translucent.TranslucentPolicy.resolve]. */
    var tier by mutableStateOf(read())
        private set

    /** The GPU did not keep up with [drawn]: one tier less from now on (never back up on its own). */
    fun lower(drawn: TranslucentTier) {
        val next = maxOf(tier, drawn.stepDown())
        if (next == tier) return
        tier = next
        prefs.edit { putString(KEY, next.name) }
    }

    /** Give the full translucency another try. */
    fun clear() {
        if (tier == TranslucentTier.LIQUID) return
        tier = TranslucentTier.LIQUID
        prefs.edit { remove(KEY) }
    }

    private fun read(): TranslucentTier =
        prefs.getString(KEY, null)?.let { name -> TranslucentTier.entries.firstOrNull { it.name == name } } ?: TranslucentTier.LIQUID

    companion object {
        /** `shared_prefs/sonora_glass_load.xml`, excluded from backup by name. */
        const val FILE = "sonora_glass_load"
        private const val KEY = "ceiling"
    }
}

/**
 * Feeds the GPU time of every frame [window] draws to a [TranslucentLoadPolicy] while a sampling tier is on screen
 * ([watch]), and calls [onStepDown] on the main thread with the tier the GPU did not keep up with. FrameMetrics
 * arrive on a thread of their own. The budget of each frame is the display's refresh period when it was drawn, so a
 * display that switches between 60 and 120 Hz is judged at the rate it drew; not FrameMetrics.DEADLINE, which counts
 * the presentation pipeline too (21-121 ms, median 44 ms, on a OnePlus 7 Pro at 60 Hz). Decisions are logged under
 * [TAG].
 */
internal class TranslucentLoadMonitor(private val window: Window, private val onStepDown: (TranslucentTier) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** Judge frames drawn at [tier]; flat translucency (or Android 11 and older) has nothing to judge. */
    fun watch(tier: TranslucentTier) {
        stop()
        if (tier == TranslucentTier.FLAT || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        start(tier)
    }

    fun stop() {
        listener?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun start(tier: TranslucentTier) {
        val policy = TranslucentLoadPolicy()
        var reported = false
        val worker = HandlerThread(THREAD).also { it.start() }
        val frames = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (reported || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val gpuMs = metrics.getMetric(FrameMetrics.GPU_DURATION) / NANOS_PER_MS
            val budgetMs = window.decorView.display?.refreshRate?.takeIf { it > 0 }?.let { 1000.0 / it } ?: DEFAULT_BUDGET_MS
            if (policy.frame(gpuMs, budgetMs)) {
                // One report per tier: the next tier gets a monitor of its own once it is on screen.
                reported = true
                Log.i(TAG, "GPU did not keep up with $tier translucent (last frame ${"%.0f".format(gpuMs)} ms, budget ${"%.1f".format(budgetMs)} ms)")
                main.post { onStepDown(tier) }
            }
        }
        window.addOnFrameMetricsAvailableListener(frames, Handler(worker.looper))
        thread = worker
        listener = frames
    }

    companion object {
        const val TAG = "SonoraTranslucent"
        private const val THREAD = "SonoraTranslucentLoad"
        private const val NANOS_PER_MS = 1_000_000.0
        private const val DEFAULT_BUDGET_MS = 1000.0 / 60
    }
}
