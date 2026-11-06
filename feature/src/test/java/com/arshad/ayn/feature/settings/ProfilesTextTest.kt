package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileTint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Profiles text both UIs show (`b3-profiles` v8, Q7). */
class ProfilesTextTest {

    @Test
    fun countsNameSubscriptionsAndPlaylistsOnlyWhenThereAreAny() {
        assertEquals("18 in history", countsText(ProfileContents(0, 0, 18)))
        assertEquals("1 subscription · 2 playlists · 96 in history", countsText(ProfileContents(1, 2, 96)))
    }

    @Test
    fun removingAnEmptyProfileNamesOnlyWatchLater() {
        assertEquals("This deletes Zaid’s Watch later. It can’t be undone.", removeText(row("Zaid", ProfileContents.Empty)))
        assertEquals(
            "This deletes Guests’s Watch later and 1 video from history. It can’t be undone.",
            removeText(row("Guests", ProfileContents(0, 0, 1))),
        )
    }

    /** PASS question 16: a kid's subscriptions are approved channels, and its approved videos go too. */
    @Test
    fun removingAKidNamesApprovedChannelsAndVideos() {
        val kid = row("Zainab", ProfileContents(1, 0, 1), kids = true).copy(kid = KidSummary(2, emptyMap()))
        assertEquals(
            "This deletes Zainab’s 1 approved channel, 2 approved videos, Watch later and 1 video from history. It can’t be undone.",
            removeText(kid),
        )
    }

    private fun row(name: String, contents: ProfileContents, kids: Boolean = false): ProfileRow =
        ProfileRow(Profile(7, name, ProfileTint.entries.first(), isOwner = false, lock = null, isKids = kids), contents)
}
