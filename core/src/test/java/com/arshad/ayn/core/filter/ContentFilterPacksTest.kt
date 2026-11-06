package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filter packs in the content filter (FPK-D2, D5, D11, D12, D13, D14). Channel ids are the
 * research report's (2026-10-29), written out here rather than read back from the pack lists.
 */
class ContentFilterPacksTest {

    @Test
    fun harmfulForKidsHidesByTitleChannelIdAndChannelName() {
        val filter = own(FilterPacks(harmfulForKids = true))

        assertFalse(filter.allows(packVideo(title = "SURPRISE EGGS opening")))
        assertFalse(filter.allows(packVideo(channelId = RYANS_WORLD)))
        assertFalse("FPK-D11", filter.allows(packVideo(channelName = "Vlad and Niki Fans")))
        assertTrue(filter.allows(packVideo()))
    }

    /** FPK-D11: a profile's own keywords still read titles alone. */
    @Test
    fun aKeywordDoesNotMatchTheChannelName() {
        assertTrue(filterOf(Keyword("vlad")).allows(packVideo(channelName = "Vlad and Niki Fans")))
    }

    @Test
    fun theOwnersPacksHideAsTheProfilesOwnDo() {
        val filter = owners(FilterPacks(harmfulForKids = true))

        assertFalse(filter.allows(packVideo(title = "Skibidi Toilet 77")))
    }

    @Test
    fun anOptionCountsOnlyWhileItsPackIsOn() {
        val cocomelon = packVideo(channelId = COCOMELON)

        assertTrue(own(FilterPacks(overstimulating = true)).allows(cocomelon))
        assertTrue("off by default (FPK-D6)", own(FilterPacks(harmfulForKids = true)).allows(cocomelon))
        assertFalse(own(FilterPacks(harmfulForKids = true, overstimulating = true)).allows(cocomelon))
    }

    /** FPK-D5: a pack-exempt channel passes that pack only. */
    @Test
    fun anExemptChannelPassesItsPackButNotAnother() {
        val filter = own(FilterPacks(harmfulForKids = true, overstimulating = true, halalOnly = true))

        assertFalse("the title alone is a pack title", filter.allows(packVideo(title = COLOURS_TITLE)))
        assertTrue(filter.allows(packVideo(title = COLOURS_TITLE, channelId = LEARN_WITH_ZAKARIA)))
        assertFalse("not exempt from Halal only", filter.allows(packVideo(title = "Colours song (Official Music Video)", channelId = LEARN_WITH_ZAKARIA)))
    }

    /** FPK-D5: an explicit channel block comes before a pack exemption. */
    @Test
    fun anExplicitChannelBlockBeatsAPackExemption() {
        val filter = ContentFilter.of(
            ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true, overstimulating = true)),
            ProfileRules(rules = listOf(StoredRule(BlockedChannel(channelUrl(LEARN_WITH_ZAKARIA), null), byOwner = false, addedAtMillis = 0))),
        )

        assertFalse(filter.allows(packVideo(title = COLOURS_TITLE, channelId = LEARN_WITH_ZAKARIA)))
    }

    @Test
    fun halalOnlyKeepsANasheedTitleAndAnInstrumentFreeChannelsMusicVideo() {
        val filter = own(FilterPacks(halalOnly = true))

        assertFalse(filter.allows(packVideo(title = "Song (Official Music Video)")))
        assertTrue("exempt title (FPK-D3)", filter.allows(packVideo(title = "Nasheed (Official Music Video)")))
        assertTrue("instrument-free channel", filter.allows(packVideo(title = "Arabic Music Video", channelId = AHMED_BUKHATIR)))
        assertFalse("a ' - Topic' channel", filter.allows(packVideo(channelName = "Someone - Topic")))
    }

    /** A channel that posts both versions keeps only the titles carrying its own no-music marker
     *  (Zain Bhikha's voice-only album, which the pack-wide exempt titles don't know). */
    @Test
    fun halalOnlyGuardsAMixedChannelByItsTitle() {
        val filter = own(FilterPacks(halalOnly = true))
        val album = "Behind Love | Allah Knows (Official Music Video)"

        assertFalse(filter.allows(packVideo(title = "Allah Knows (Official Music Video)", channelId = ZAIN_BHIKHA)))
        assertTrue(filter.allows(packVideo(title = album, channelId = ZAIN_BHIKHA)))
        assertFalse("another channel's", filter.allows(packVideo(title = album)))
    }

    @Test
    fun halalOnlysContestedOptionsAreOffUntilTurnedOn() {
        val christmas = packVideo(title = "Christmas lights in the city")

        assertTrue(own(FilterPacks(halalOnly = true)).allows(christmas))
        assertFalse(own(FilterPacks(halalOnly = true, festivals = true)).allows(christmas))
    }

    @Test
    fun islamicOnlyKeepsOnlyItsEnabledChannelsVideos() {
        val filter = own(FilterPacks(islamicOnly = true))

        assertTrue("on for adults", filter.allows(packVideo(channelId = MUFTI_MENK)))
        assertFalse("listed, off by default", filter.allows(packVideo(channelId = YASIR_QADHI)))
        assertFalse("not listed", filter.allows(packVideo()))
    }

    @Test
    fun islamicOnlyKeepsOnlyItsEnabledChannelsPagesAndNoPlaylistCards() {
        val filter = own(FilterPacks(islamicOnly = true))

        assertTrue(filter.allowsChannel(channelUrl(MUFTI_MENK)))
        assertFalse(filter.allowsChannel(channelUrl(RYANS_WORLD)))
        assertFalse(filter.allowsPlaylistCards)
        assertTrue("YouTube's search still runs; its answers are filtered", filter.allowsDiscovery)
    }

    /** FPK-D12: a channel's switch overrides its default either way. */
    @Test
    fun islamicChannelSwitchesOverrideTheirDefaults() {
        val filter = own(FilterPacks(islamicOnly = true, islamicChannels = mapOf(YASIR_QADHI to true, MUFTI_MENK to false)))

        assertTrue(filter.allows(packVideo(channelId = YASIR_QADHI)))
        assertFalse(filter.allows(packVideo(channelId = MUFTI_MENK)))
    }

    @Test
    fun islamicOnlyGuardsAMixedChannelAndBlocksAlafasysPoetry() {
        val filter = own(FilterPacks(islamicOnly = true, islamicChannels = mapOf(OMAR_AND_HANA to true)))

        assertFalse(filter.allows(packVideo(title = "Alphabet song", channelId = OMAR_AND_HANA)))
        assertTrue(filter.allows(packVideo(title = "Alphabet song (No Music)", channelId = OMAR_AND_HANA)))
        assertTrue("its page stays; the videos pass one by one", filter.allowsChannel(channelUrl(OMAR_AND_HANA)))
        assertFalse(filter.allows(packVideo(title = POETRY_TITLE, channelId = ALAFASY)))
        assertTrue(filter.allows(packVideo(title = "Surah Al-Kahf", channelId = ALAFASY)))
    }

    /** KP-D12: a video either setter's allow-list leaves out stays hidden. */
    @Test
    fun bothSettersIslamicListsMustKeepAVideo() {
        val filter = ContentFilter.of(
            ProfileSettings(filterPacks = FilterPacks(islamicOnly = true)),
            ProfileRules(restrictions = ProfileRestrictions(filterPacks = FilterPacks(islamicOnly = true, islamicChannels = mapOf(MUFTI_MENK to false)))),
        )

        assertFalse(filter.allows(packVideo(channelId = MUFTI_MENK)))
        assertTrue(filter.allows(packVideo(channelId = OMAR_SULEIMAN)))
    }

    /** A block-list keeps what it can't judge (KP-D15). */
    @Test
    fun aBlockPackPassesWhatNamesNoChannelOrTitle() {
        val filter = own(FilterPacks(harmfulForKids = true, halalOnly = true))

        assertTrue(filter.allowsChannel(null))
        assertTrue(filter.allowsPlaylistCards)
        assertTrue("a handle URL", filter.allows(packVideo(channelUrl = HANDLE_URL)))
        assertTrue("no title", filter.allows(VideoId(VIDEO_ID), title = null, channelUrl = channelUrl(CHANNEL_ID), durationSeconds = 600))
    }

    /** An allow-list fails what it can't judge (KP-D15). */
    @Test
    fun islamicOnlyFailsWhatNamesNoListedChannelOrAGuardedChannelsTitle() {
        val filter = own(FilterPacks(islamicOnly = true))

        assertFalse(filter.allowsChannel(null))
        assertFalse("no channel", filter.allows(packVideo(channelUrl = null)))
        assertFalse("a handle URL", filter.allows(packVideo(channelUrl = HANDLE_URL)))
        assertFalse("a guarded channel, no title", filter.allows(VideoId(VIDEO_ID), title = null, channelUrl = channelUrl(ZAIN_BHIKHA), durationSeconds = 600))
        assertTrue("an unguarded channel, no title", filter.allows(VideoId(VIDEO_ID), title = null, channelUrl = channelUrl(MUFTI_MENK), durationSeconds = 600))
    }

    /** FPK-D14: a video approved one by one passes every pack. */
    @Test
    fun aKidsApprovedVideoPassesEveryPack() {
        val approved = ApprovedVideo(VIDEO_ID, "Giant Egg Hunt", null, null, null, null)

        assertTrue(kidsWithPacks(FilterPacks(harmfulForKids = true), emptySet(), approved).allows(packVideo(title = "Surprise eggs", channelId = RYANS_WORLD)))
        assertTrue("an unlisted channel's", kidsWithPacks(FilterPacks(islamicOnly = true), emptySet(), approved).allows(packVideo()))
    }

    /** FPK-D14: an approved channel's videos still pass through the packs. */
    @Test
    fun aKidsApprovedChannelStillPassesThroughThePacks() {
        val ryans = kidsWithPacks(FilterPacks(harmfulForKids = true), setOf(channelUrl(RYANS_WORLD)))
        val omarAndHana = kidsWithPacks(FilterPacks(islamicOnly = true), setOf(channelUrl(OMAR_AND_HANA)))

        assertFalse("a pack's channel block", ryans.allows(packVideo(channelId = RYANS_WORLD)))
        assertFalse(ryans.allowsChannel(channelUrl(RYANS_WORLD)))
        assertFalse("the Islamic guard", omarAndHana.allows(packVideo(title = "Alphabet song", channelId = OMAR_AND_HANA)))
        assertTrue(omarAndHana.allows(packVideo(title = "Alphabet song (No Music)", channelId = OMAR_AND_HANA)))
    }

    @Test
    fun anExplicitBlockBeatsAKidsApproval() {
        val kids = kidsWithPacks(FilterPacks(harmfulForKids = true), emptySet(), ApprovedVideo(VIDEO_ID, "Song", null, null, null, null), BlockedVideo(VIDEO_ID, null, null))

        assertFalse(kids.allows(packVideo()))
    }

    /** FPK-D2, D13: on a kids profile Islamic only keeps its enabled channels (kids defaults) and
     *  the owner's approved channels. */
    @Test
    fun aKidsProfilesIslamicChannelsCountAsApproved() {
        val approved = channelUrl(CHANNEL_ID)
        val kids = kidsWithPacks(FilterPacks(islamicOnly = true), setOf(approved))

        assertTrue(kids.allows(packVideo(channelId = ONE4KIDS)))
        assertTrue(kids.allowsChannel(channelUrl(ONE4KIDS)))
        assertTrue(kids.allows(packVideo(channelUrl = "$approved/videos")))
        assertFalse("lectures start off for kids", kids.allows(packVideo(channelId = MUFTI_MENK)))
        assertFalse("without the pack it isn't approved", kidsWithPacks(FilterPacks(), setOf(approved)).allows(packVideo(channelId = ONE4KIDS)))
    }

    /** FPK-D13: a kids profile's Home and Search list its Islamic channels, in the pack's order. */
    @Test
    fun aKidsProfilesPackApprovedChannelsAreItsKeptIslamicOnes() {
        val kids = kidsWithPacks(FilterPacks(islamicOnly = true), setOf(channelUrl(CHANNEL_ID)))

        assertEquals(IslamicOnly.channels.filter { it.onForKids }.map { it.id }, kids.packApprovedChannels.map { it.id })
        assertEquals(channelUrl(ONE4KIDS), kids.packApprovedChannels.single { it.id == ONE4KIDS }.url)
    }

    /** KP-D12: a channel either setter switched off isn't one of the kid's channels. */
    @Test
    fun aKidsPackApprovedChannelsFollowBothSettersLists() {
        val kids = ContentFilter.of(
            ProfileSettings(filterPacks = FilterPacks(islamicOnly = true, islamicChannels = mapOf(ALAFASY to false))),
            ProfileRules(restrictions = ProfileRestrictions(filterPacks = FilterPacks(islamicOnly = true, islamicChannels = mapOf(ONE4KIDS to false))), kids = true),
        )

        val ids = kids.packApprovedChannels.map { it.id }

        assertEquals(IslamicOnly.channels.filter { it.onForKids && it.id != ALAFASY && it.id != ONE4KIDS }.map { it.id }, ids)
    }

    @Test
    fun onlyAKidsProfileWithIslamicOnlyHasPackApprovedChannels() {
        assertEquals("another profile", emptyList<String>(), own(FilterPacks(islamicOnly = true)).packApprovedChannels.map { it.id })
        assertEquals("without the pack", emptyList<String>(), kidsWithPacks(FilterPacks(harmfulForKids = true), emptySet()).packApprovedChannels.map { it.id })
    }

    @Test
    fun aTitleAloneIsCheckedByThePacksTitleRules() {
        val filter = own(FilterPacks(harmfulForKids = true, halalOnly = true))

        assertFalse(filter.allowsTitle("Skibidi Toilet 77"))
        assertFalse(filter.allowsTitle("Song (Official Music Video)"))
        assertTrue(filter.allowsTitle("Nasheed (Official Music Video)"))
        assertTrue(filter.allowsTitle("A calm walk in the woods"))
        assertFalse("Islamic only's blocks", own(FilterPacks(islamicOnly = true)).allowsTitle(POETRY_TITLE))
    }

    @Test
    fun aChannelCardHidesByAPacksChannelName() {
        val filter = own(FilterPacks(harmfulForKids = true))
        val card = ChannelItem("Kids Diana Show Fans", channelUrl(CHANNEL_ID), null, subscriberCount = -1, description = null, verified = false)

        assertFalse(filter.allows(card))
        assertTrue(filter.allows(card.copy(name = "Calm Woods")))
    }

    /** KP-D15: caches key on filters, so choices that filter alike are equal. */
    @Test
    fun filtersWithTheSamePacksAreEqual() {
        val packs = FilterPacks(harmfulForKids = true)
        val islamic = FilterPacks(islamicOnly = true)

        assertEquals(own(packs), owners(packs))
        assertEquals(own(packs).hashCode(), owners(packs).hashCode())
        assertNotEquals(own(packs), own(packs.copy(overstimulating = true)))
        assertEquals("options alone turn nothing on", filterOf(), own(FilterPacks(overstimulating = true)))
        assertEquals(
            "a switch at its default, and an id no longer listed",
            own(islamic),
            own(islamic.copy(islamicChannels = mapOf(MUFTI_MENK to true, "UCzzzzzzzzzzzzzzzzzzzzzz" to true))),
        )
    }

    /** The recorded "lofi hip hop" search through the seam: its channel ids and names reach the
     *  packs, and Halal only keeps none of its music. */
    @Test
    fun aRecordedSearchPageCarriesTheChannelIdAndNameThePacksRead() = runBlocking<Unit> {
        val videos = NewPipeAccess(downloader = FixtureDownloader("search")).search(FIXED_SEARCH_QUERY).items.filterIsInstance<VideoItem>()
        val filter = own(FilterPacks(halalOnly = true))
        val calm = "A calm walk in the woods"

        val lofiGirl = videos.first { it.uploaderName == "Lofi Girl" }
        assertFalse("listed music channel by id", filter.allows(lofiGirl.copy(title = calm, uploaderName = "Calm")))
        val chillhop = videos.first { it.uploaderName == "Chillhop Music" }
        assertFalse("a name ending in Music", filter.allows(chillhop.copy(title = calm)))
        assertTrue(filter.allows(chillhop.copy(title = calm, uploaderName = "Chillhop")))
        assertEquals(emptyList<VideoItem>(), videos.filter { filter.allows(it) })
    }
}

private const val RYANS_WORLD = "UChGJGhZ9SOOHvBB0Y4DOO_w"
private const val COCOMELON = "UCbCmjCuTUZos6Inko4u57UQ"
private const val LEARN_WITH_ZAKARIA = "UCtlcIZVBdFPSAtCoNZsTusg"
private const val AHMED_BUKHATIR = "UC-2pqFUafqKCnACEfQasZGg"
private const val ZAIN_BHIKHA = "UCTV8sHvXq5VUiTGfZv9JWjg"
private const val MUFTI_MENK = "UCNB_OaI4524fASt8h0IL8dw"
private const val OMAR_SULEIMAN = "UCtm8rtofLSnaIBi3noB0INg"
private const val YASIR_QADHI = "UClUa7-iHJNKEM2e_zWYSPQg"
private const val OMAR_AND_HANA = "UC178EmfQAV3OT-UpuO6WUMg"
private const val ALAFASY = "UCmMcOjsVehVlEOteyrhjI2Q"
private const val ONE4KIDS = "UC5vfWTTPnKdFp-8s0KbqJhw"

private const val COLOURS_TITLE = "Learn colours in Arabic for kids"
private const val POETRY_TITLE = "معلقة امرئ القيس"
private const val HANDLE_URL = "https://www.youtube.com/@someone"

private fun channelUrl(id: String): String = "https://www.youtube.com/channel/$id"

private fun own(packs: FilterPacks): ContentFilter = ContentFilter.of(ProfileSettings(filterPacks = packs), ProfileRules())

private fun owners(packs: FilterPacks): ContentFilter =
    ContentFilter.of(ProfileSettings(), ProfileRules(restrictions = ProfileRestrictions(filterPacks = packs)))

private fun kidsWithPacks(packs: FilterPacks, approvedChannels: Set<String>, vararg ownerRules: ContentRule): ContentFilter = ContentFilter.of(
    ProfileSettings(),
    ProfileRules(
        restrictions = ProfileRestrictions(filterPacks = packs),
        rules = ownerRules.map { StoredRule(it, byOwner = true, addedAtMillis = 0) },
        kids = true,
        approvedChannels = approvedChannels,
    ),
)

private fun packVideo(
    id: String = VIDEO_ID,
    title: String = "A calm walk in the woods",
    channelId: String = "UCbbbbbbbbbbbbbbbbbbbbbb",
    channelUrl: String? = channelUrl(channelId),
    channelName: String = "Someone",
): VideoItem = video(id = id, title = title, channelUrl = channelUrl).copy(uploaderName = channelName)
