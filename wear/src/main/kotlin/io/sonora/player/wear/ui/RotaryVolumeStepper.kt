package io.sonora.player.wear.ui

import kotlin.math.abs

/**
 * Turns the crown's scroll pixels into whole volume steps: each [pixelsPerStep] of rotation is one step, and what is
 * left over is kept for the next event, so slow turns still add up.
 */
class RotaryVolumeStepper(private val pixelsPerStep: Float) {
    private var pending = 0f

    init {
        require(pixelsPerStep > 0f) { "pixelsPerStep must be positive" }
    }

    /** Adds [pixels] (positive turns up) and returns the whole steps now due (negative turns down). */
    fun add(pixels: Float): Int {
        pending += pixels
        val steps = (abs(pending) / pixelsPerStep).toInt()
        if (steps == 0) return 0
        val signed = if (pending > 0f) steps else -steps
        pending -= signed * pixelsPerStep
        return signed
    }

    fun reset() {
        pending = 0f
    }
}
