package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.patternProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {

    @Test
    fun aKeywordHidesTheWholeWordInAnyCaseButNotALongerWord() {
        val filter = filterOf(Keyword("prank"))

        assertFalse(filter.allows(video(title = "PRANK WAR gone wrong")))
        assertTrue(filter.allows(video(title = "The pranksters of Paris")))
    }

    @Test
    fun keywordsFoldCurlyQuotesAndCompatibilityForms() {
        val filter = filterOf(Keyword("don't stop"))

        assertFalse("curly apostrophe", filter.allows(video(title = "DON\u2019T STOP now")))
        assertFalse("full-width letters fold under NFKC", filter.allows(video(title = "\uFF44\uFF4F\uFF4E't stop")))
    }

    @Test
    fun aKeywordIsLiteralTextNotRegexSyntax() {
        val filter = filterOf(Keyword("c++"))

        assertFalse(filter.allows(video(title = "Learn c++ in a day")))
        assertTrue(filter.allows(video(title = "Learn cpp in a day")))
    }

    @Test
    fun aPatternMatchesTitlesIgnoringCase() {
        val filter = filterOf(Pattern("\\bfree\\s+v-?bucks\\b"))

        assertFalse(filter.allows(video(title = "FREE VBUCKS glitch")))
        assertTrue(filter.allows(video(title = "Free games this week")))
    }

    @Test
    fun aPatternThatDoesntCompileIsNamedAndMatchesNothing() {
        val bad = "\\bminecraft\\s(free|hack\\b"

        assertNotNull(patternProblem(bad))
        assertNull(patternProblem("\\bskibidi\\w*"))
        assertTrue(filterOf(Pattern(bad)).allows(video(title = "minecraft free hack")))
    }

    @Test
    fun aBlockedChannelIsMatchedInAnyUrlForm() {
        val filter = filterOf(BlockedChannel("https://www.youtube.com/channel/$CHANNEL_ID", "fern"))

        assertFalse(filter.allows(video(channelUrl = "https://m.youtube.com/channel/$CHANNEL_ID/videos")))
        assertFalse(filter.allowsChannel("http://youtube.com/channel/$CHANNEL_ID"))
        assertTrue("an item with no channel passes a block-list", filter.allows(video(channelUrl = null)))
    }

    @Test
    fun aBlockedVideoIsHidden() {
        val filter = filterOf(BlockedVideo(VIDEO_ID, "t", null))

        assertFalse(filter.allows(video()))
        assertTrue(filter.allows(video(id = "bbbbbbbbbbb")))
    }

    @Test
    fun theStricterLengthBoundOfOwnAndOwnersWins() {
        val filter = ContentFilter.of(
            ProfileSettings(lengthLimits = LengthLimits(minMinutes = 1, maxMinutes = 60)),
            ProfileRules(restrictions = ProfileRestrictions(lengthLimits = LengthLimits(minMinutes = 3, maxMinutes = 120))),
        )

        assertFalse("under the owner's 3 min", filter.allows(video(seconds = 150)))
        assertTrue(filter.allows(video(seconds = 30 * 60)))
        assertFalse("over the profile's own 60 min", filter.allows(video(seconds = 61 * 60)))
        assertTrue("an unknown length passes", filter.allows(video(seconds = -1)))
        assertTrue("a live stream has no length", filter.allows(video(seconds = 0, live = true)))
    }

    @Test
    fun liveAndMembersOnlyHideByEitherSetter() {
        val own = ContentFilter.of(ProfileSettings(hideLive = true), ProfileRules())
        val owners = ContentFilter.of(ProfileSettings(), ProfileRules(restrictions = ProfileRestrictions(hideMembersOnly = true)))

        assertFalse(own.allows(video(live = true)))
        assertFalse(owners.allows(video(membersOnly = true)))
        assertTrue(owners.allows(video(live = true)))
    }

    /** PAF-D9: YouTube refuses live streams under Restricted Mode (PASS s18), so lists hide them. */
    @Test
    fun restrictedModeHidesLiveCardsWhoeverSetIt() {
        val own = ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules())
        val owners = ContentFilter.of(ProfileSettings(), ProfileRules(restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict)))

        assertFalse(own.allows(video(live = true)))
        assertFalse(owners.allows(video(live = true)))
        assertTrue("a video still shows", own.allows(video()))
    }

    /** A kids profile is Strict whatever its rows say (KP-D6): an approved channel's live card hides. */
    @Test
    fun aKidsProfilesForcedStrictHidesLiveCards() {
        val approved = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
        val kids = kidsFilter(setOf(approved))

        assertFalse(kids.allows(video(channelUrl = approved, live = true)))
        assertTrue(kids.allows(video(channelUrl = approved)))
    }

    /** The resolve gate lets a live page hidden only by Restricted Mode through to its own refusal,
     *  which names the cause (LIVE-D13 kids, PAF-D3); Hide live streams still refuses it there. */
    @Test
    fun aLivePageHiddenOnlyByRestrictedModePassesTheResolveGate() {
        val strict = ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules())
        val hidden = ContentFilter.of(ProfileSettings(hideLive = true), ProfileRules())

        assertTrue(strict.allows(video(live = true).id, "A stream", null, durationSeconds = null, live = true, listing = false))
        assertFalse(hidden.allows(video(live = true).id, "A stream", null, durationSeconds = null, live = true, listing = false))
    }

    @Test
    fun theOwnersHideShortsAloneHidesShorts() {
        assertFalse(ContentFilter.of(ProfileSettings(showShorts = true), ProfileRules()).hidesShorts)
        assertTrue(ContentFilter.of(ProfileSettings(showShorts = true), ProfileRules(restrictions = ProfileRestrictions(hideShorts = true))).hidesShorts)
    }

    @Test
    fun restrictedModeIsTheStricterOfOwnAndOwners() {
        val strict = ProfileRestrictions(restrictedMode = RestrictedMode.Strict)

        assertEquals(RestrictedMode.Off, ContentFilter.of(ProfileSettings(), ProfileRules()).restrictedMode)
        assertEquals(RestrictedMode.Strict, ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules()).restrictedMode)
        assertEquals(RestrictedMode.Strict, ContentFilter.of(ProfileSettings(), ProfileRules(restrictions = strict)).restrictedMode)
        assertEquals("fail closed before any profile opens", RestrictedMode.Strict, ContentFilter.REFUSE_ALL.restrictedMode)
    }

    /** Equal filters filter alike, so caches of filtered answers key on them (KP-D15): who set a
     *  rule and when don't count, the rules and the level do. */
    @Test
    fun filtersThatFilterAlikeAreEqual() {
        val own = filterOf(Keyword("prank"), BlockedChannel("https://www.youtube.com/channel/$CHANNEL_ID", "A"))
        val owners = ContentFilter.of(
            ProfileSettings(speedPercent = 150),
            ProfileRules(rules = listOf(StoredRule(BlockedChannel("https://www.youtube.com/channel/$CHANNEL_ID", null), true, 9), StoredRule(Keyword("prank"), true, 5))),
        )

        assertEquals(own, owners)
        assertEquals(own.hashCode(), owners.hashCode())
        assertNotEquals(own, filterOf(Keyword("prank")))
        assertNotEquals(filterOf(), ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules()))
    }

    /** KP-D3, D15: a kids profile keeps an approved channel's videos and approved videos only; a
     *  video with no channel passes by its id alone, and a block wins over an approval. */
    @Test
    fun aKidsProfileKeepsOnlyWhatTheOwnerApproved() {
        val approved = "https://www.youtube.com/channel/$CHANNEL_ID"
        val kids = kidsFilter(
            approvedChannels = setOf(approved),
            ApprovedVideo("approvedvid", "Song", null, null, null, null),
            BlockedVideo("blockedvid1", null, null),
        )

        assertTrue(kids.allows(video(channelUrl = "$approved/videos")))
        assertFalse(kids.allows(video()))
        assertTrue(kids.allows(video(id = "approvedvid")))
        assertTrue(kids.allows(video(id = "approvedvid", channelUrl = null)))
        assertFalse(kids.allows(video(channelUrl = null)))
        assertFalse(kids.allows(video(id = "blockedvid1", channelUrl = approved)))
        assertTrue(kids.allowsChannel(approved))
        assertFalse(kids.allowsChannel("https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"))
        assertFalse(kids.allowsChannel(null))
    }

    /** KP-D6, D10, D11: the kids preset ignores its rows' Shorts and Restricted Mode, and turns
     *  off YouTube's lists, suggestions, comments and playlist cards. */
    @Test
    fun aKidsProfileIsStrictWithoutShortsDiscoveryOrComments() {
        val kids = kidsFilter(emptySet())

        assertTrue(kids.hidesShorts)
        assertEquals(RestrictedMode.Strict, kids.restrictedMode)
        assertFalse(kids.allowsDiscovery)
        assertFalse(kids.allowsSuggestions)
        assertFalse(kids.allowsComments)
        assertFalse(kids.allowsPlaylistCards)
        assertTrue(filterOf().allowsDiscovery && filterOf().allowsPlaylistCards)
    }

    @Test
    fun aTitleAloneIsCheckedByKeywordsAndPatterns() {
        val filter = filterOf(Keyword("prank"), Pattern("free\\s+v-?bucks"))

        assertFalse(filter.allowsTitle("The best PRANK ever"))
        assertFalse(filter.allowsTitle("Free V-Bucks today"))
        assertTrue(filter.allowsTitle("A calm walk in the woods"))
    }

    @Test
    fun refuseAllPassesNothing() {
        assertFalse(ContentFilter.REFUSE_ALL.allows(video()))
        assertFalse(ContentFilter.REFUSE_ALL.allowsChannel(null))
        assertFalse(ContentFilter.REFUSE_ALL.allowsTitle("A video"))
    }

    @Test
    fun foldTitleUsesTheRootLocale() {
        val device = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
        try {
            assertEquals("title", foldTitle("TITLE"))
        } finally {
            java.util.Locale.setDefault(device)
        }
    }
}

internal const val VIDEO_ID = "aaaaaaaaaaa"
internal const val CHANNEL_ID = "UCaaaaaaaaaaaaaaaaaaaaaa"

internal fun kidsFilter(approvedChannels: Set<String>, vararg ownerRules: ContentRule): ContentFilter = ContentFilter.of(
    ProfileSettings(showShorts = true),
    ProfileRules(rules = ownerRules.map { StoredRule(it, byOwner = true, addedAtMillis = 0) }, kids = true, approvedChannels = approvedChannels),
)

internal fun filterOf(vararg rules: ContentRule, byOwner: Boolean = false): ContentFilter =
    ContentFilter.of(ProfileSettings(), ProfileRules(rules = rules.map { StoredRule(it, byOwner, addedAtMillis = 0) }))

internal fun video(
    id: String = VIDEO_ID,
    title: String = "A video",
    channelUrl: String? = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb",
    seconds: Long = 600,
    live: Boolean = false,
    membersOnly: Boolean = false,
): VideoItem = VideoItem(
    id = VideoId(id),
    title = title,
    uploaderName = "Someone",
    uploaderUrl = channelUrl,
    durationSeconds = seconds,
    viewCount = -1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = live,
    membersOnly = membersOnly,
)
