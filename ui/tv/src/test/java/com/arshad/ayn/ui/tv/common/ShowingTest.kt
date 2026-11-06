package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberShowing] over a screen whose identity is its name: a change inside one showing keeps
 * its key; a screen shown again, after nothing or another screen, gets a new one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Screen(val name: String, val typed: String = "")

    private val shown = mutableStateOf<Screen?>(null)
    private val keys = mutableListOf<Any?>()

    private fun show(screen: Screen?): Any? {
        compose.runOnUiThread { shown.value = screen }
        compose.waitForIdle()
        return keys.last()
    }

    private fun start() {
        compose.setContent {
            val showing = rememberShowing(shown.value) { it.name }
            keys += showing?.key
        }
        compose.waitForIdle()
    }

    @Test
    fun aChangeInsideOneShowingKeepsItsKey() {
        start()
        val first = show(Screen("rename"))

        assertEquals(first, show(Screen("rename", typed = "Ma")))
    }

    @Test
    fun theSameScreenShownAgainAfterNothingGetsANewKey() {
        start()
        val first = show(Screen("refused"))

        assertNull(show(null))
        assertNotEquals(first, show(Screen("refused")))
    }

    @Test
    fun theSameScreenShownAgainAfterAnotherGetsANewKey() {
        start()
        val first = show(Screen("refused"))
        val reading = show(Screen("reading"))

        val again = show(Screen("refused"))

        assertNotEquals(first, reading)
        assertNotEquals(first, again)
    }
}
