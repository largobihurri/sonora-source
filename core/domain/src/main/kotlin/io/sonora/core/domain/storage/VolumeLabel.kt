package io.sonora.core.domain.storage

import java.util.Locale

/**
 * A FAT volume label: 1-11 characters valid in a FAT short name, trimmed and upper-cased. Only printable ASCII is
 * accepted, so the label reads the same whatever OEM code page the host (a PC or a CDJ) assumes.
 */
@JvmInline
value class VolumeLabel private constructor(val text: String) {

    override fun toString(): String = text

    companion object {
        const val MAX_LENGTH = 11

        /** Characters a FAT short name cannot hold, besides control characters (fatgen103, "FAT Directory Structure"). */
        private const val FORBIDDEN = "\"*+,./:;<=>?[\\]|"

        /** Why [input] cannot be a label, or null when [of] accepts it; lets a UI explain the problem while typing. */
        fun validate(input: String): VolumeLabelError? {
            val text = normalize(input)
            if (text.isEmpty()) return VolumeLabelError.Empty
            text.firstOrNull { !isAllowed(it) }?.let { return VolumeLabelError.InvalidCharacter(it) }
            return if (text.length > MAX_LENGTH) VolumeLabelError.TooLong(text.length) else null
        }

        /** The label for [input]; throws [IllegalArgumentException] when [validate] reports an error. */
        fun of(input: String): VolumeLabel {
            validate(input)?.let { throw IllegalArgumentException("Invalid volume label '$input': $it") }
            return VolumeLabel(normalize(input))
        }

        fun ofOrNull(input: String): VolumeLabel? = if (validate(input) == null) VolumeLabel(normalize(input)) else null

        private fun normalize(input: String) = input.trim().uppercase(Locale.ROOT)

        private fun isAllowed(character: Char) = character in ' '..'~' && character !in FORBIDDEN
    }
}

sealed interface VolumeLabelError {
    data object Empty : VolumeLabelError

    data class TooLong(val length: Int, val maxLength: Int = VolumeLabel.MAX_LENGTH) : VolumeLabelError

    data class InvalidCharacter(val character: Char) : VolumeLabelError
}
