package io.sonora.core.domain.playback.output

import kotlinx.coroutines.flow.Flow

/** The audio outputs the phone can play to, read from the system. */
interface AudioOutputMonitor {
    /** The current outputs; emits again whenever a device is connected or removed, while collected. */
    val snapshots: Flow<AudioOutputSnapshot>

    /** The outputs right now. */
    fun current(): AudioOutputSnapshot
}
