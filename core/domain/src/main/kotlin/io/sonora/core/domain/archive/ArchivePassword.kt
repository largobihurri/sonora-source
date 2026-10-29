package io.sonora.core.domain.archive

import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Random

/** Whether a password is strong enough to protect an archive, and why not. */
sealed interface PasswordCheck {
    data object Strong : PasswordCheck

    data class TooShort(val minLength: Int) : PasswordCheck

    /** Kinds are lowercase letters, uppercase letters, digits and anything else (symbols, spaces). */
    data class TooFewKinds(val required: Int, val found: Int) : PasswordCheck
}

/**
 * The strength rule for archive passwords. A ZIP's AES key comes from the password through a fast key derivation
 * (PBKDF2, 1,000 rounds), so guessing is cheap for an attacker holding the file: the rule asks for length first.
 */
object PasswordStrength {

    const val MIN_LENGTH: Int = 12
    const val MIN_KINDS: Int = 3

    fun check(password: CharSequence): PasswordCheck {
        if (password.length < MIN_LENGTH) return PasswordCheck.TooShort(MIN_LENGTH)
        val kinds = kindsIn(password)
        return if (kinds < MIN_KINDS) PasswordCheck.TooFewKinds(MIN_KINDS, kinds) else PasswordCheck.Strong
    }

    /** Checks a password kept as characters (so it can be wiped), without copying it into a String. */
    fun check(password: CharArray): PasswordCheck = check(CharBuffer.wrap(password))

    private fun kindsIn(password: CharSequence): Int {
        var lower = false
        var upper = false
        var digit = false
        var other = false
        for (c in password) {
            when {
                Character.isLowerCase(c) -> lower = true
                Character.isUpperCase(c) -> upper = true
                Character.isDigit(c) -> digit = true
                else -> other = true
            }
        }
        return listOf(lower, upper, digit, other).count { it }
    }
}

/** Strong random passwords to copy and send by another channel, easy to read aloud or type from a screen. */
object PasswordGenerator {

    /** Letters and digits without the look-alikes 0/O and 1/l/I. 57 characters: about 5.8 bits each. */
    const val ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** 24 characters: about 140 bits. */
    const val LENGTH: Int = 24

    /** A new password that passes [PasswordStrength]; draws again in the rare case one misses a kind. */
    fun generate(random: Random = SecureRandom()): CharArray {
        while (true) {
            val password = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            if (PasswordStrength.check(password) == PasswordCheck.Strong) return password
            password.fill('\u0000')
        }
    }
}
