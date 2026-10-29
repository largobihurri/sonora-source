package io.sonora.feature.converter

import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.model.AudioFormat

/**
 * The formats the output picker lists, in order: the XDJ-700 target, then each format with settings ([format]). The
 * watch's Opus profile is not a user's choice and is never listed.
 */
enum class FormatChoice(val format: AudioFormat?) {
    XDJ_700(null),
    AIFF(AudioFormat.AIFF),
    WAV(AudioFormat.WAV),
    FLAC(AudioFormat.FLAC),
    AAC(AudioFormat.AAC_M4A),
    OPUS(AudioFormat.OPUS),
    MP3(AudioFormat.MP3),
    ;

    /** The target picking this format gives: [current] when it already is this format, else its default settings. */
    fun pick(current: OutputTarget): OutputTarget = when {
        of(current) == this -> current
        format == null -> OutputTarget.Xdj700
        else -> OutputTargets.defaultFor(format)
    }

    companion object {
        /** The choice [target] shows as. */
        fun of(target: OutputTarget): FormatChoice =
            if (target == OutputTarget.Xdj700) XDJ_700 else entries.first { it.format == target.format }
    }
}

/** Setting changes the picker makes that are more than a `copy`. */
object OutputChoices {

    /** MP3 at a constant or a variable bitrate: unchanged when already so, else that mode's default. */
    fun withMp3Vbr(target: OutputTarget.Mp3, vbr: Boolean): OutputTarget.Mp3 = when {
        (target.mode is Mp3Mode.Vbr) == vbr -> target
        vbr -> OutputTarget.Mp3(OutputTarget.Mp3.DEFAULT_VBR)
        else -> OutputTarget.Mp3(OutputTarget.Mp3.DEFAULT_CBR)
    }
}
