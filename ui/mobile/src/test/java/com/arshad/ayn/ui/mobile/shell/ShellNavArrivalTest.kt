package com.arshad.ayn.ui.mobile.shell

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which starts of a page are arrivals (PERF P9c): a tab tapped to, the app coming back; never Back.
 *  A tab re-tap under a page asks no scroll; closing a page by key leaves what covers it. */
class ShellNavArrivalTest {
    private val nav = ShellNav(mutableStateOf(Tab.Home), Tab.entries.associateWith { NavBackStack<NavKey>(TabKey(it)) })
    private val subscriptions = TabKey(Tab.Subscriptions)

    @Test
    fun tappingToATabIsAnArrivalOnce() {
        nav.onTabTap(Tab.Subscriptions)

        assertTrue(nav.consumeArrival(subscriptions))
        assertFalse(nav.consumeArrival(subscriptions))
    }

    @Test
    fun backFromAPageIsNoArrivalEvenAfterTheAppLeftOnIt() {
        nav.onTabTap(Tab.Subscriptions)
        nav.consumeArrival(subscriptions)
        nav.open(ChannelKey(ChannelRoute("https://www.youtube.com/channel/UCa")))
        nav.onAppStopped()

        nav.back()

        assertFalse(nav.consumeArrival(subscriptions))
    }

    @Test
    fun tappingTheShownTabUnderAPageAsksNoScrollForBackToFind() {
        nav.open(ChannelKey(ChannelRoute("https://www.youtube.com/channel/UCa")))

        nav.onTabTap(Tab.Home)
        nav.back()

        assertEquals(null, nav.reselected)
    }

    @Test
    fun theAppComingBackToAPageIsAnArrival() {
        nav.onTabTap(Tab.Subscriptions)
        nav.consumeArrival(subscriptions)

        nav.onAppStopped()

        assertTrue(nav.consumeArrival(subscriptions))
    }

    @Test
    fun closingAPageByKeyLeavesThePageOverItAndAnAbsentKeyChangesNothing() {
        val saved = SavedPlaylistKey(SavedPlaylistRoute(7))
        val channel = ChannelKey(ChannelRoute("https://www.youtube.com/channel/UCa"))
        nav.open(saved)
        nav.open(channel)

        nav.close(saved)
        nav.close(SavedPlaylistKey(SavedPlaylistRoute(8)))

        assertEquals(listOf(TabKey(Tab.Home), channel), nav.stacks.getValue(Tab.Home).toList())
    }
}
