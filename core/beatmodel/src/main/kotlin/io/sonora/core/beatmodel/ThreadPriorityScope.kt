package io.sonora.core.beatmodel

import android.os.Process

/**
 * Runs a block with the calling thread at another Linux nice value, and puts the old one back afterwards. ONNX Runtime
 * starts the threads of a session's intra-op pool on the thread that creates the session, and a new thread starts with
 * its creator's priority, so creating the session inside [runAt] gives the whole pool the analysis' priority without
 * touching the creating thread for good (it may be a shared pool thread).
 *
 * @param get the calling thread's nice value.
 * @param set applies a nice value to the calling thread.
 */
class ThreadPriorityScope(private val get: () -> Int, private val set: (Int) -> Unit) {

    fun <T> runAt(priority: Int, block: () -> T): T {
        val previous = get()
        set(priority)
        try {
            return block()
        } finally {
            set(previous)
        }
    }

    companion object {
        /** Changes nothing. */
        val NONE: ThreadPriorityScope = ThreadPriorityScope(get = { 0 }, set = {})

        /** The Android scheduler: `Process.setThreadPriority` on the calling thread; a refusal leaves the priority as it was. */
        fun android(): ThreadPriorityScope = ThreadPriorityScope(
            get = { Process.getThreadPriority(Process.myTid()) },
            set = { nice -> runCatching { Process.setThreadPriority(nice) } },
        )
    }
}
