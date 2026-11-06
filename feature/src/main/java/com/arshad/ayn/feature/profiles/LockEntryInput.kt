package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.PIN_DIGITS
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.SEQUENCE_PRESSES
import com.arshad.ayn.core.lock.UnlockResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock-entry input (§ B3p) behind the unlock screen, the owner gate and setting a lock. It
 * holds the presses or digits (only their count reaches [UnlockEntry]) and drives the entry its
 * host shows through [read] and [write]; [read] is `null` while no entry shows, and input is
 * ignored then. A sequence submits on OK (fewer than 6 presses is a hint), a PIN on its 4th
 * digit. [startTry] checks each entry as a counted try; [startSetup] takes a new lock, entered
 * twice, counting nothing. A check in flight is never cancelled (a cancelled correct try would
 * still count, M5-D15): input is off until it lands. Main thread, like its host ViewModel.
 * [timeSource] runs the wait countdown and must keep counting while the device sleeps.
 */
class LockEntryInput(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val read: () -> UnlockEntry?,
    private val write: ((UnlockEntry) -> UnlockEntry) -> Unit,
) {
    private var mode: Mode? = null
    private val presses = mutableListOf<Press>()
    private val digits = StringBuilder()
    private var countdown: Job? = null

    /** Each entry of a [kind] lock is a try that [check] counts; a correct one hands its pass to
     *  [opened] after the input is cleared. */
    fun <P> startTry(kind: LockKind, check: suspend (LockSecret) -> UnlockResult<P>, opened: suspend (P) -> Unit) {
        clear()
        mode = Mode.Try(kind) { secret ->
            when (val result = check(secret)) {
                is UnlockResult.Opened -> UnlockResult.Opened(suspend { opened(result.pass) })
                is UnlockResult.Wrong -> result
                is UnlockResult.Waiting -> result
            }
        }
    }

    /** A new [kind] lock: entered, then entered again ([UnlockEntry.confirming]). When the two
     *  match, [confirmed] gets it and input stays off (the host moves on); when they don't, the
     *  entry starts over with [UnlockMessage.Mismatch] and nothing is kept. */
    fun startSetup(kind: LockKind, confirmed: suspend (LockSecret) -> Unit) {
        clear()
        mode = Mode.Setup(kind, confirmed)
    }

    /** Input stops and everything entered is forgotten. */
    fun clear() {
        mode = null
        clearHeld()
    }

    fun press(press: Press) {
        val entry = accepting(LockKind.Sequence) ?: return
        if (presses.size >= SEQUENCE_PRESSES.last) return
        presses += press
        write { entry.copy(entered = presses.size, message = null, rejectedDots = 0) }
    }

    fun digit(digit: Char) {
        val entry = accepting(LockKind.Pin) ?: return
        if (digit !in '0'..'9' || digits.length >= PIN_DIGITS) return
        digits.append(digit)
        write { entry.copy(entered = digits.length, message = null, rejectedDots = 0) }
        if (digits.length == PIN_DIGITS) submit(LockSecret.Pin(digits.toString()))
    }

    /** OK on the sequence pad: too few presses is a hint, not a try. */
    fun submitSequence() {
        val entry = accepting(LockKind.Sequence) ?: return
        if (presses.size !in SEQUENCE_PRESSES) {
            write { entry.copy(message = UnlockMessage.TooShort) }
            return
        }
        submit(LockSecret.Sequence(presses.toList()))
    }

    /** An erase key (the keypad's, the touch sequence pad's): the last digit or press goes;
     *  `false` when there was none to erase (or no entry shows). */
    fun erase(): Boolean = mode?.kind?.let(::eraseLast) == true

    /** BACK: erases the last press or digit, and is taken without effect while an entry is
     *  checked or saved; `false` when nothing was erased otherwise (none entered, a wait, no
     *  entry showing), so the host decides where BACK goes. */
    fun back(): Boolean = read()?.checking == true || erase()

    /** The UI has shaken the rejected dots away. */
    fun rejectionShown() {
        write { it.copy(rejectedDots = 0) }
    }

    /** Shows a [total] wait (input off) counting down to zero. */
    fun waitFor(total: Duration) {
        clearHeld()
        val ends = timeSource.markNow() + total
        write { it.copy(entered = 0, wait = total, waitShown = total, message = null) }
        countdown = scope.launch {
            while (true) {
                val left = -ends.elapsedNow()
                if (left <= Duration.ZERO) break
                write { it.copy(wait = left) }
                delay(minOf(left, 1.seconds))
            }
            write { it.copy(wait = null, waitShown = null) }
        }
    }

    private fun eraseLast(kind: LockKind): Boolean {
        val entry = accepting(kind) ?: return false
        val held = when (kind) {
            LockKind.Sequence -> presses.removeLastOrNull()?.let { presses.size }
            LockKind.Pin -> digits.takeIf { it.isNotEmpty() }?.let { it.deleteAt(it.lastIndex).length }
        } ?: return false
        write { entry.copy(entered = held, message = null) }
        return true
    }

    private fun submit(secret: LockSecret) {
        when (val current = mode) {
            is Mode.Try -> checkTry(current, secret)
            is Mode.Setup -> confirmEntry(current, secret)
            null -> Unit
        }
    }

    private fun checkTry(trying: Mode.Try, secret: LockSecret) {
        val tried = presses.size + digits.length
        write { it.copy(checking = true, message = null) }
        scope.launch {
            val result = trying.attempt(secret)
            clearHeld()
            when (result) {
                is UnlockResult.Opened -> result.pass()
                is UnlockResult.Wrong -> {
                    write { entry ->
                        entry.copy(
                            entered = 0,
                            checking = false,
                            rejectedDots = tried,
                            rejections = entry.rejections + 1,
                            message = UnlockMessage.Wrong(result.triesBeforeWait).takeIf { result.wait == null },
                        )
                    }
                    result.wait?.let(::waitFor)
                }
                is UnlockResult.Waiting -> {
                    write { it.copy(entered = 0, checking = false) }
                    waitFor(result.remaining)
                }
            }
        }
    }

    private fun confirmEntry(setup: Mode.Setup, secret: LockSecret) {
        val entered = Entered(presses.toList(), digits.toString())
        val tried = presses.size + digits.length
        presses.clear()
        digits.clear()
        val first = setup.first
        when {
            first == null -> {
                setup.first = entered
                write { UnlockEntry(confirming = true, rejections = it.rejections) }
            }
            first == entered -> {
                setup.first = null
                write { it.copy(checking = true, message = null) }
                scope.launch { setup.confirmed(secret) }
            }
            else -> {
                setup.first = null
                write {
                    UnlockEntry(rejectedDots = tried, rejections = it.rejections + 1, message = UnlockMessage.Mismatch)
                }
            }
        }
    }

    private fun accepting(kind: LockKind): UnlockEntry? {
        val entry = read() ?: return null
        return entry.takeIf { mode?.kind == kind && !entry.checking && entry.wait == null }
    }

    private fun clearHeld() {
        presses.clear()
        digits.clear()
        countdown?.cancel()
        countdown = null
    }

    /** What the first entry of a new lock held, kept to compare with the second. Printed as its
     *  length only, like [LockSecret]. */
    private data class Entered(val presses: List<Press>, val digits: String) {
        override fun toString(): String = "Entered(${presses.size + digits.length})"
    }

    private sealed interface Mode {
        val kind: LockKind

        /** [attempt] counts and checks a try; a correct one carries what to run once it lands. */
        class Try(
            override val kind: LockKind,
            val attempt: suspend (LockSecret) -> UnlockResult<suspend () -> Unit>,
        ) : Mode

        class Setup(override val kind: LockKind, val confirmed: suspend (LockSecret) -> Unit) : Mode {
            var first: Entered? = null
        }
    }
}
