package com.arshad.ayn.feature.home

import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Subscription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [kidsPicks] over fake stores: the kid's approved videos as Home's "Picked for you" row (KP-D10). */
class KidsPicksTest {
    private val filters = FakeFilterStore()
    private val profiles = FakeProfileStore(
        listOf(
            Profile(OWNER, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin),
            Profile(KID, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true),
        ),
    )

    @Test
    fun picksAreTheApprovedVideosNewestApprovalFirstUnderTheOwnersName() = runTest {
        approve("aaaaaaaaaa1" to 1L, "bbbbbbbbbb1" to 2L)

        val picks = kidsPicks(KID, filters, profiles, flowOf(passAll)).first()

        assertEquals("Arshad", picks.ownerName)
        assertEquals(listOf("bbbbbbbbbb1", "aaaaaaaaaa1"), picks.videos.map { it.id.value })
        assertEquals("Approved bbbbbbbbbb1", picks.videos.first().title)
    }

    @Test
    fun aPickTheSessionFilterHidesIsLeftOut() = runTest {
        approve("aaaaaaaaaa1" to 1L, "bbbbbbbbbb1" to 2L)
        val blocking = ContentFilter.of(
            ProfileSettings(),
            ProfileRules(rules = listOf(StoredRule(BlockedVideo("aaaaaaaaaa1", null, null), byOwner = true, addedAtMillis = 3))),
        )

        val picks = kidsPicks(KID, filters, profiles, flowOf(blocking)).first()

        assertEquals(listOf("bbbbbbbbbb1"), picks.videos.map { it.id.value })
    }

    /** FPK-D13: Islamic content only's kids channels, named from the pack's list. */
    @Test
    fun picksCarryThePackChannels() = runTest {
        val first = IslamicOnly.channels.first { it.onForKids }

        val picks = kidsPicks(KID, filters, profiles, flowOf(islamicKids())).first()

        assertEquals(IslamicOnly.channels.count { it.onForKids }, picks.packChannels.size)
        assertEquals("https://www.youtube.com/channel/${first.id}" to first.name, picks.packChannels.first().let { it.channelUrl to it.name })
    }

    @Test
    fun picksCarryNoPackChannelsWithoutThePack() = runTest {
        val picks = kidsPicks(KID, filters, profiles, flowOf(passAll)).first()

        assertEquals(emptyList<Subscription>(), picks.packChannels)
    }

    /** A block still wins: a listed channel the owner blocked isn't one of the kid's channels. */
    @Test
    fun aPackChannelTheOwnerBlockedIsLeftOut() {
        val blocked = IslamicOnly.channels.first { it.onForKids }

        val channels = packSubscriptions(islamicKids(StoredRule(BlockedChannel(blocked.url, blocked.name), byOwner = true, addedAtMillis = 1)))

        assertEquals(IslamicOnly.channels.count { it.onForKids } - 1, channels.size)
        assertTrue(channels.none { it.channelUrl == blocked.url })
    }

    @Test
    fun aPackChannelTheKidApprovedIsListedOnce() {
        val approved = Subscription("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa", "Approved", null, 1, 1)
        val pack = Subscription("https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb", "Pack", null, 0, 0)

        assertEquals(listOf(approved, pack), withPackChannels(listOf(approved), listOf(approved.copy(name = "Pack copy"), pack)))
    }

    private fun islamicKids(vararg rules: StoredRule): ContentFilter = ContentFilter.of(
        ProfileSettings(),
        ProfileRules(restrictions = ProfileRestrictions(filterPacks = FilterPacks(islamicOnly = true)), rules = rules.toList(), kids = true),
    )

    private fun approve(vararg videos: Pair<String, Long>) {
        val rules = videos.map { (id, at) ->
            StoredRule(ApprovedVideo(id, "Approved $id", "Bluey", null, null, durationSeconds = 420), byOwner = true, addedAtMillis = at)
        }
        filters.rulesById.value = mapOf(KID to ProfileRules(rules = rules, kids = true))
    }

    private companion object {
        const val OWNER = 1L
        const val KID = 4L
        val passAll: ContentFilter = ContentFilter.of(ProfileSettings(), ProfileRules())
    }
}
