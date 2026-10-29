package io.sonora.core.rekordbox.setting

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How the players name keys: "Classic" (`Am`, `E`) or "Alphanumeric" (`8A`, `12B`), as in rekordbox's preferences. */
enum class KeyDisplayFormat(val code: Int) {
    CLASSIC(1),
    ALPHANUMERIC(2),
}

/**
 * Builds `PIONEER/DEVSETTING.DAT`, the player settings rekordbox puts on every exported stick (layout and checksum per
 * rekordcrate's `setting.rs`): u32 0x60, the brand, software and version strings in 32-byte NUL-padded fields, u32 32,
 * the 32 data bytes, their CRC16-XMODEM and u16 0.
 *
 * Every setting keeps rekordbox's default (half-waveform overview, blue waveform, playhead in the centre) except the
 * key display, so a player that honours the file shows the export's keys the way the app does.
 */
object DevSettingWriter {
    private const val STRING_FIELD_BYTES = 0x20
    private const val DATA_BYTES = 32
    private const val FILE_BYTES = 4 + 3 * STRING_FIELD_BYTES + 4 + DATA_BYTES + 4
    private const val DATA_AT = 4 + 3 * STRING_FIELD_BYTES + 4
    private const val KEY_DISPLAY_IN_DATA = 12

    /** rekordbox's default data block; byte [KEY_DISPLAY_IN_DATA] is the key display format. */
    private val DEFAULT_DATA = byteArrayOf(
        0x78, 0x56, 0x34, 0x12, 0x01, 0x00, 0x00, 0x00, 0x01, // unknown, fixed
        0x01, // overview waveform: half waveform
        0x01, // waveform colour: blue
        0x01, // unknown, fixed
        0x01, // key display format
        0x01, // waveform current position: centre
    ) + ByteArray(18)

    fun write(keyDisplay: KeyDisplayFormat = KeyDisplayFormat.ALPHANUMERIC): ByteArray {
        val data = DEFAULT_DATA.copyOf().also { it[KEY_DISPLAY_IN_DATA] = keyDisplay.code.toByte() }
        check(data.size == DATA_BYTES)
        val buffer = ByteBuffer.allocate(FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(3 * STRING_FIELD_BYTES)
        listOf("PIONEER DJ", "rekordbox", "6.6.1").forEach { buffer.put(it.toByteArray(Charsets.US_ASCII).copyOf(STRING_FIELD_BYTES)) }
        buffer.putInt(DATA_BYTES)
        check(buffer.position() == DATA_AT)
        buffer.put(data)
        buffer.putShort(Crc16Xmodem.of(data).toShort())
        buffer.putShort(0)
        return buffer.array()
    }
}

/** CRC-16/XMODEM: polynomial 0x1021, initial value 0, no reflection, no final XOR. */
internal object Crc16Xmodem {
    private const val POLYNOMIAL = 0x1021

    fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLYNOMIAL else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }
}
