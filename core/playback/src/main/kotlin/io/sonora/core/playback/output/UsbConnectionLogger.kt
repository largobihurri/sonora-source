package io.sonora.core.playback.output

import io.sonora.core.domain.playback.output.AudioOutputLog
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Writes a line to [log] whenever a USB audio device appears in, or leaves, the outputs it is given (for logcat tag
 * `SonoraAudioOut`): what the device reports and whether it offers bit-perfect output. Devices already present when
 * collecting starts count as connected.
 */
internal class UsbConnectionLogger(private val log: (String) -> Unit) {
    suspend fun collect(snapshots: Flow<AudioOutputSnapshot>) {
        var previous = AudioOutputSnapshot.EMPTY
        snapshots.collect { current ->
            current.newUsbDevices(previous).forEach { log("USB output connected: ${AudioOutputLog.describe(it)}") }
            previous.newUsbDevices(current).forEach { log("USB output removed: ${AudioOutputLog.describe(it)}") }
            previous = current
        }
    }
}
