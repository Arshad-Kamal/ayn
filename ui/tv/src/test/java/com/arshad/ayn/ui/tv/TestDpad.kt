package com.arshad.ayn.ui.tv

import android.os.SystemClock
import android.view.KeyEvent as AndroidViewKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.arshad.ayn.core.lock.Press
import org.junit.Assert.assertTrue

/**
 * tv-material surfaces fire onClick on ENTER-class key events of the focused element
 * (handleDPadEnter; the OnClick semantics action and touch do not invoke it): focus, then
 * press DPAD center down + up (the click fires on up).
 */
internal fun SemanticsNodeInteraction.dpadSelect() {
    performSemanticsAction(SemanticsActions.RequestFocus)
    performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(AndroidViewKeyEvent.ACTION_DOWN, AndroidViewKeyEvent.KEYCODE_DPAD_CENTER)))
    performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(AndroidViewKeyEvent.ACTION_UP, AndroidViewKeyEvent.KEYCODE_DPAD_CENTER)))
}

/**
 * Holds OK on this element: the press, its first repeat (where tv-material fires a long click),
 * then, once what the hold opened has settled, the release.
 */
internal fun SemanticsNodeInteraction.dpadLongPress(waitForIdle: () -> Unit) {
    performSemanticsAction(SemanticsActions.RequestFocus)
    val down = SystemClock.uptimeMillis()
    val center = AndroidViewKeyEvent.KEYCODE_DPAD_CENTER
    performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(down, down, AndroidViewKeyEvent.ACTION_DOWN, center, 0)))
    performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(down, down + LONG_PRESS_MS, AndroidViewKeyEvent.ACTION_DOWN, center, 1)))
    waitForIdle()
    performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(down, down + LONG_PRESS_MS + 100, AndroidViewKeyEvent.ACTION_UP, center, 0)))
    waitForIdle()
}

private const val LONG_PRESS_MS = 500L

/** The lock tests' D-pad sequence. */
internal val TEST_SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)

internal fun keyOf(press: Press): Key = when (press) {
    Press.Up -> Key.DirectionUp
    Press.Down -> Key.DirectionDown
    Press.Left -> Key.DirectionLeft
    Press.Right -> Key.DirectionRight
}

/** Presses [sequence] on this D-pad sequence pad, then OK. */
internal fun SemanticsNodeInteraction.enterSequence(sequence: List<Press>) {
    sequence.forEach { press -> performKeyInput { pressKey(keyOf(press)) } }
    performKeyInput { pressKey(Key.DirectionCenter) }
}

/** The remote's BACK, through the activity's dispatcher as the platform delivers it. */
internal fun AndroidComposeTestRule<*, out ComponentActivity>.pressBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/** The remote's [code] (an `android.view.KeyEvent` key code), down then up, at the root. */
internal fun ComposeTestRule.press(code: Int) {
    onRoot().performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(AndroidViewKeyEvent.ACTION_DOWN, code)))
    onRoot().performKeyPress(ComposeKeyEvent(AndroidViewKeyEvent(AndroidViewKeyEvent.ACTION_UP, code)))
    waitForIdle()
}

/** A session and its controller hop through the paused main looper: drains it until [what] holds. */
internal fun ComposeTestRule.waitFor(what: () -> Boolean) {
    repeat(20) {
        if (what()) return
        waitForIdle()
    }
    assertTrue("condition never held", what())
}
