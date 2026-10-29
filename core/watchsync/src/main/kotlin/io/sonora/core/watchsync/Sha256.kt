package io.sonora.core.watchsync

import java.io.File
import java.security.MessageDigest

/** SHA-256 as lowercase hex, the checksum the manifest carries. */
object Sha256 {
    private val HEX = "0123456789abcdef".toCharArray()
    private val VALID = Regex("[0-9a-fA-F]{64}")

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = toHex(newDigest().digest(bytes))

    /** The digest of the whole file, read in blocks. */
    fun hexOfFile(file: File): String {
        val digest = newDigest()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun isValidHex(text: String): Boolean = VALID.matches(text)
}
