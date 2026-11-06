package com.arshad.ayn.core.lock

/** How a profile is locked (§ B3p): one kind per profile, the D-pad sequence by default. */
enum class LockKind(val key: String) {
    Sequence("sequence"),
    Pin("pin"),
    ;

    companion object {
        /** The kind stored as [key]. A lock whose kind this build can't read stays a lock
         *  (as a sequence), so it can't open without its secret. */
        fun of(key: String?): LockKind = entries.firstOrNull { it.key == key } ?: Sequence
    }
}

/** One press of a D-pad sequence: the arrows only. */
enum class Press(internal val symbol: Char) {
    Up('U'),
    Down('D'),
    Left('L'),
    Right('R'),
}

/**
 * What a person enters for a lock. Never logged or shown: [toString] names only the kind.
 * The UI checks lengths before building one (OK with too few presses is a hint, not a try).
 */
sealed class LockSecret(val kind: LockKind) {
    internal abstract val symbols: String

    class Sequence(presses: List<Press>) : LockSecret(LockKind.Sequence) {
        init {
            require(presses.size in SEQUENCE_PRESSES) { "a sequence has $SEQUENCE_PRESSES presses" }
        }

        override val symbols: String = presses.joinToString(separator = "") { it.symbol.toString() }
    }

    class Pin(digits: String) : LockSecret(LockKind.Pin) {
        init {
            require(digits.length == PIN_DIGITS && digits.all { it in '0'..'9' }) { "a PIN is $PIN_DIGITS digits" }
        }

        override val symbols: String = digits
    }

    /** The hashed form: the kind is part of it, so a secret of the other kind never matches. */
    internal fun material(): CharArray = "${kind.key}:$symbols".toCharArray()

    override fun toString(): String = "LockSecret(${kind.key})"
}

val SEQUENCE_PRESSES: IntRange = 6..8
const val PIN_DIGITS = 4
