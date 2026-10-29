package io.sonora.tools.calibration.runner

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Decodes reference audio on the host with ffmpeg (a subprocess, one thread: `-threads 1`) into the 32-bit float PCM
 * the pipeline reads, at the file's own rate and channel count; the pipeline mixes down and resamples itself, as it
 * does on the phone. The host has no qm-dsp, so this decoder is only paired with the beat model.
 *
 * ffmpeg's MP3 demuxer skips the encoder delay and padding announced in a LAME/Xing header (gapless playback); the
 * Android decoder may not, and rekordbox's choice is what the calibration measures per format.
 */
class FfmpegDecoder(
    private val ffmpeg: String = "ffmpeg",
    private val ffprobe: String = "ffprobe",
    private val log: File? = null,
) {

    /** Sample rate and channel count of the first audio stream. */
    data class Format(val sampleRateHz: Int, val channelCount: Int)

    /** An open decode: read [pcm], then close (which stops ffmpeg if it is still running). */
    class Decode(private val process: Process, val format: Format, val pcm: F32lePcmSource) : AutoCloseable {
        override fun close() {
            process.inputStream.close()
            if (!process.waitFor(EXIT_WAIT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        /** Waits for ffmpeg to end (call after the whole stream was read) and returns its exit status, -1 on a timeout. */
        fun awaitExit(): Int {
            if (!process.waitFor(EXIT_WAIT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1
            }
            return process.exitValue()
        }
    }

    fun probe(file: Path): Format {
        val process = ProcessBuilder(
            ffprobe, "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels", "-of", "default=noprint_wrappers=1", file.toString(),
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw IOException("ffprobe failed on $file: $text")
        }
        val values = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
        val rate = values["sample_rate"]?.toIntOrNull() ?: throw IOException("No sample rate for $file: $text")
        val channels = values["channels"]?.toIntOrNull() ?: throw IOException("No channel count for $file: $text")
        return Format(rate, channels)
    }

    fun open(file: Path): Decode {
        val format = probe(file)
        val builder = ProcessBuilder(
            ffmpeg, "-nostdin", "-hide_banner", "-v", "error", "-threads", "1",
            "-i", file.toString(), "-map", "0:a:0", "-vn", "-sn", "-dn",
            "-f", "f32le", "-acodec", "pcm_f32le", "-",
        )
        builder.redirectError(log?.let { ProcessBuilder.Redirect.appendTo(it) } ?: ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        process.outputStream.close()
        val pcm = F32lePcmSource(process.inputStream.buffered(PIPE_BUFFER), format.sampleRateHz, format.channelCount)
        return Decode(process, format, pcm)
    }

    private companion object {
        const val PROBE_TIMEOUT_S = 30L
        const val EXIT_WAIT_S = 10L
        const val PIPE_BUFFER = 1 shl 16
    }
}
