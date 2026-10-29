package io.sonora.player.wear.ui

/**
 * When the headphones prompt shows. Playback waits for an output (the watch's speaker is never used), and the prompt asks
 * for headphones once per wait: dismissing it hides it until the wait ends and a new one starts, so it does not nag.
 */
data class HeadphonesPromptState(private val dismissed: Boolean = false) {

    fun isVisible(waitingForOutput: Boolean): Boolean = waitingForOutput && !dismissed

    /** The user closed the prompt, or went to the Bluetooth settings from it. */
    fun dismissed(): HeadphonesPromptState = copy(dismissed = true)

    /** Playback's waiting changed: when it ended, the next wait asks again. */
    fun onWaitingChanged(waitingForOutput: Boolean): HeadphonesPromptState =
        if (waitingForOutput) this else copy(dismissed = false)
}
