package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.store.Impression
import com.arshad.ayn.core.store.WatchEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking on the recorded watch pages' related lists ([RecordedLists]), at a fixed [NOW].
 * Expected orders are worked by hand from [ForYouWeights]: with no history every channel is new
 * and every card's novelty is equal, so one seed's list ranks in YouTube's order.
 */
class ForYouRankerTest {
    private val chapters: List<VideoItem> get() = RecordedLists.chapters
    private val chaptersSeed = seed(CHAPTERS_VIDEO_ID)
    private val chaptersReason = ForYouReason(VideoId(CHAPTERS_VIDEO_ID), SeedKind.Watched, "seed $CHAPTERS_VIDEO_ID")

    @Test
    fun oneSeedsListKeepsYouTubesOrderWithAtMostTwoCardsPerChannel() {
        val ranking = rank(listOf(chaptersSeed), mapOf(CHAPTERS_VIDEO_ID to chapters))

        // Veritasium's third and fourth (A5w-dEgIU1M, wt4p2oalmRY) are over the cap; 13 picks.
        assertEquals(
            listOf(
                "MiUHjLxm3V0", "twDvxNooI_4", "EkLmxmUfgBU", "_L1Rim7-IPo", "eNkxTjirY-Q", "isNCO2P24fA", "ugPCuLZz350",
                "Tt6WQYtefXA", "OETnuwwsv9U", "0QWP4IZOu0I", "C_sUSQGT83A", "Oz7GXguhCfA", "s-Vv9qq4iT0",
            ),
            ranking.ids(),
        )
        assertTrue(ranking.picks.all { it.reason == ForYouReason(VideoId(CHAPTERS_VIDEO_ID), SeedKind.Watched, "seed $CHAPTERS_VIDEO_ID") })
    }

    /** By hand: Oz7GXguhCfA sits 13th in one list (0.455) and 6th in another (0.667); its sum beats
     *  any first place (1.0), and its reason is the seed that adds most. */
    @Test
    fun aVideoInSeveralSeedsListsLeadsWithTheStrongerSeedsReason() {
        val saved = seed("savedseed01", kind = SeedKind.Saved, subject = "The Enigma machine")
        val savedList = RecordedLists.live.take(5) + chapters[12] + RecordedLists.live.drop(5)

        val ranking = rank(listOf(chaptersSeed, saved), mapOf(CHAPTERS_VIDEO_ID to chapters, "savedseed01" to savedList))

        assertEquals(ForYouPick(chapters[12], ForYouReason(VideoId("savedseed01"), SeedKind.Saved, "The Enigma machine")), ranking.picks.first())
    }

    /** By hand: the half-weight seed's best card (0.6) is far below the other's second (0.927), yet
     *  the seeds alternate. */
    @Test
    fun seedsTakeTurns() {
        val ranking = rank(
            listOf(chaptersSeed, seed("storyseed01", weight = 0.5)),
            mapOf(CHAPTERS_VIDEO_ID to chapters, "storyseed01" to RecordedLists.storyboards),
        )

        assertEquals(listOf("MiUHjLxm3V0", "yPYZpwSpKmA", "twDvxNooI_4", "ElpQ5ipdvOg"), ranking.ids().take(4))
    }

    @Test
    fun watchedBouncedInProgressHiddenHiddenChannelShortAndLiveVideosNeverShow() {
        // The recorded live card has no length; one is given so only the live rule can drop it.
        val live = RecordedLists.live[15].copy(durationSeconds = 3_600)
        val list = listOf(live) + chapters.mapIndexed { i, video -> if (i == 4) video.copy(durationSeconds = 59) else video }

        val ranking = rank(
            listOf(chaptersSeed),
            mapOf(CHAPTERS_VIDEO_ID to list),
            history = listOf(
                entry("MiUHjLxm3V0", NOW - 1.days),
                entry("twDvxNooI_4", NOW - 1.hours, positionMs = 10_000, watched = false),
                entry("ugPCuLZz350", NOW - 2.hours, positionMs = 120_000, watched = false),
            ),
            hiddenVideos = setOf(VideoId("EkLmxmUfgBU")),
            hiddenChannels = setOf("https://www.youtube.com/channel/UCY1kMZp36IQSyNx_9h4mpCg"),
        )

        assertTrue(live.isLive)
        val ids = ranking.ids()
        assertEquals("isNCO2P24fA", ids.first())
        assertTrue(ids.none { it in setOf(live.id.value, "eNkxTjirY-Q", "MiUHjLxm3V0", "twDvxNooI_4", "ugPCuLZz350", "EkLmxmUfgBU", "_L1Rim7-IPo") })
    }

    @Test
    fun channelsCompareInAnyUrlForm() {
        val list = chapters.mapIndexed { i, video ->
            when (i) {
                2 -> video.copy(uploaderUrl = "https://m.youtube.com/channel/UCODHrzPMGbNv67e84WDZhQQ")
                7 -> video.copy(uploaderUrl = "http://m.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA/videos")
                else -> video
            }
        }

        val ranking = rank(
            listOf(chaptersSeed),
            mapOf(CHAPTERS_VIDEO_ID to list),
            hiddenChannels = setOf("https://www.youtube.com/channel/UCODHrzPMGbNv67e84WDZhQQ"),
        )

        // fern's two cards are hidden; Veritasium's second (another URL form) fills its cap.
        assertEquals(
            listOf(
                "MiUHjLxm3V0", "twDvxNooI_4", "_L1Rim7-IPo", "eNkxTjirY-Q", "isNCO2P24fA", "ugPCuLZz350", "Tt6WQYtefXA",
                "OETnuwwsv9U", "0QWP4IZOu0I", "C_sUSQGT83A", "Oz7GXguhCfA", "s-Vv9qq4iT0", "BXLGV0Sj0n8",
            ),
            ranking.ids(),
        )
    }

    /** By hand: the subscribed second card scores (0.727 + 0.1) × 1.3 = 1.075 against the first's
     *  1.0; the subscription is given in another URL form. */
    @Test
    fun aSubscribedChannelLiftsItsCard() {
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters.take(2))

        val subscribed = rank(listOf(chaptersSeed), lists, subscribed = setOf("https://www.youtube.com/channel/UCsX5upzW0Tk2riDY1WB887w/featured"))
        val unsubscribed = rank(listOf(chaptersSeed), lists)

        assertEquals(listOf("twDvxNooI_4", "MiUHjLxm3V0"), subscribed.ids())
        assertEquals(listOf("MiUHjLxm3V0", "twDvxNooI_4"), unsubscribed.ids())
    }

    /** By hand: a day-old upload in second place scores 0.927 × 1.293 = 1.199, a year-old first 1.0. */
    @Test
    fun aNewUploadOvertakesAnOldOne() {
        val list = listOf(chapters[0].copy(uploadDate = NOW - 365.days), chapters[1].copy(uploadDate = NOW - 1.days))

        val ranking = rank(listOf(chaptersSeed), mapOf(CHAPTERS_VIDEO_ID to list))

        assertEquals(listOf("twDvxNooI_4", "MiUHjLxm3V0"), ranking.ids())
    }

    @Test
    fun anUnopenedCardShownRecentlyOrThreeTimesDrops() {
        val impressions = listOf(
            shown("MiUHjLxm3V0", count = 1, ago = 1.hours),
            shown("twDvxNooI_4", count = 1, ago = 13.hours),
            shown("EkLmxmUfgBU", count = 3, ago = 2.days),
        )

        val ranking = rank(listOf(chaptersSeed), mapOf(CHAPTERS_VIDEO_ID to chapters), impressions = impressions)

        assertEquals(listOf("twDvxNooI_4", "_L1Rim7-IPo", "eNkxTjirY-Q"), ranking.ids().take(3))
    }

    @Test
    fun theKeptHeroStaysFirstUnderSixHoursEvenWhenRotatedAndItsSeedYieldsTheRowsFirstCard() {
        val pin = PinnedPick(ForYouPick(chapters[5], chaptersReason), since = NOW - 5.hours)

        val ranking = rank(
            listOf(chaptersSeed, seed("storyseed01", weight = 0.5)),
            mapOf(CHAPTERS_VIDEO_ID to chapters, "storyseed01" to RecordedLists.storyboards),
            impressions = listOf(shown("isNCO2P24fA", count = 2, ago = 1.hours)),
            pinned = pin,
        )

        assertEquals(pin.pick, ranking.picks.first())
        assertEquals(pin, ranking.pinned)
        // The other seed's best (0.6) leads the row over the hero's seed's next (0.995): seeds alternate.
        assertEquals(listOf("isNCO2P24fA", "yPYZpwSpKmA", "MiUHjLxm3V0"), ranking.ids().take(3))
        assertEquals(1, ranking.ids().count { it == "isNCO2P24fA" })
    }

    @Test
    fun aKeptHeroNoListHoldsAnyMoreStaysFirst() {
        val pin = PinnedPick(ForYouPick(RecordedLists.live[0], ForYouReason(VideoId("goneseed001"), SeedKind.Saved, "gone")), since = NOW - 1.hours)

        val ranking = rank(listOf(chaptersSeed), mapOf(CHAPTERS_VIDEO_ID to chapters), pinned = pin)

        assertEquals(pin.pick, ranking.picks.first())
        assertEquals(pin, ranking.pinned)
    }

    /** The hero is Veritasium's second card: its third and fourth stay out, and 12 cards follow. */
    @Test
    fun theHeroCountsTowardItsChannelsTwoAndTheThirteenPicks() {
        val pin = PinnedPick(ForYouPick(chapters[7], chaptersReason), since = NOW - 1.hours)

        val ranking = rank(listOf(chaptersSeed), mapOf(CHAPTERS_VIDEO_ID to chapters), pinned = pin)

        assertEquals(
            listOf(
                "Tt6WQYtefXA", "MiUHjLxm3V0", "twDvxNooI_4", "EkLmxmUfgBU", "_L1Rim7-IPo", "eNkxTjirY-Q", "isNCO2P24fA",
                "ugPCuLZz350", "OETnuwwsv9U", "0QWP4IZOu0I", "C_sUSQGT83A", "Oz7GXguhCfA", "s-Vv9qq4iT0",
            ),
            ranking.ids(),
        )
    }

    @Test
    fun thePinEndsAtSixHoursWhenWatchedOrHidden() {
        val pin = PinnedPick(ForYouPick(chapters[5], chaptersReason), since = NOW - 1.hours)
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters)

        val outcomes = mapOf(
            "six hours old" to rank(listOf(chaptersSeed), lists, pinned = pin.copy(since = NOW - 6.hours)),
            "watched" to rank(listOf(chaptersSeed), lists, pinned = pin, history = listOf(entry("isNCO2P24fA", NOW - 1.hours))),
            "not interested" to rank(listOf(chaptersSeed), lists, pinned = pin, hiddenVideos = setOf(VideoId("isNCO2P24fA"))),
            "channel hidden" to rank(
                listOf(chaptersSeed),
                lists,
                pinned = pin,
                hiddenChannels = setOf("https://www.youtube.com/channel/UChUAaNhjdc1aN5f_29BPrhw"),
            ),
        )

        outcomes.forEach { (case, ranking) ->
            assertEquals(case, "MiUHjLxm3V0", ranking.picks.first().video.id.value)
            assertEquals(case, PinnedPick(ranking.picks.first(), NOW), ranking.pinned)
        }
    }

    /**
     * By hand, two equal seeds' first cards, no history: three of the first seed's four cards were
     * shown (2 days ago, so not rotated), its novelty is (1 + 0.25) / 2 and its first card scores
     * 0.925 against the other seed's 1.0. Unshown, the two tie and the id decides.
     */
    @Test
    fun aLessUsedSeedsCardLeads() {
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters.take(4), "storyseed01" to RecordedLists.storyboards.take(4))
        val seeds = listOf(chaptersSeed, seed("storyseed01"))
        val shownCards = chapters.drop(1).take(3).map { shown(it.id.value, count = 1, ago = 2.days) }

        val used = rank(seeds, lists, impressions = shownCards)
        val unused = rank(seeds, lists)

        assertEquals("yPYZpwSpKmA", used.ids().first())
        assertEquals("MiUHjLxm3V0", unused.ids().first())
    }

    /**
     * By hand, a Veritasium card first and a new channel's second, the seed's own watch on
     * Veritasium: at no skips 0.99 against 0.927; after 10 skips novelty's share is 0.7 and the
     * new channel leads, 0.973 against 0.715; a real watch after the skips resets them.
     */
    @Test
    fun skipsShiftTowardNewChannelsUntilARealWatch() {
        val history = listOf(entry(CHAPTERS_VIDEO_ID, NOW - 1.days, uploaderUrl = VERITASIUM))
        val skips = (1..10).map { n -> shown("skipped%04d".format(n), count = 3, ago = 2.hours) }
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters.take(2))

        val calm = rank(listOf(chaptersSeed), lists, history = history)
        val bored = rank(listOf(chaptersSeed), lists, history = history, impressions = skips)
        val reset = rank(listOf(chaptersSeed), lists, history = history + entry("rewatched01", NOW - 1.hours), impressions = skips)

        assertEquals(listOf("MiUHjLxm3V0", "twDvxNooI_4"), calm.ids())
        assertEquals(listOf("twDvxNooI_4", "MiUHjLxm3V0"), bored.ids())
        assertEquals(listOf("MiUHjLxm3V0", "twDvxNooI_4"), reset.ids())
    }

    /**
     * Past 10 skips the ramp holds at 0.7. By hand, with Veritasium subscribed: its first card
     * scores 1.3 × (0.3 + 0.35) = 0.845 and the new channel's 13th (Oz7GXguhCfA) 0.836, so the
     * order stays; an uncapped share at 20 skips (1.2) would sink the Veritasium card to 0.52.
     */
    @Test
    fun pastTenSkipsTheRampHolds() {
        fun skips(count: Int): List<Impression> = (1..count).map { n -> shown("skipped%04d".format(n), count = 3, ago = 2.hours) }
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters)
        val subscribed = setOf(VERITASIUM)

        val ten = rank(listOf(chaptersSeed), lists, subscribed = subscribed, impressions = skips(10))
        val twenty = rank(listOf(chaptersSeed), lists, subscribed = subscribed, impressions = skips(20))

        assertTrue(ten.ids().indexOf("MiUHjLxm3V0") in 0..<ten.ids().indexOf("Oz7GXguhCfA"))
        assertEquals(ten.ids(), twenty.ids())
    }

    /** By hand: three Veritasium cards shown and never opened put its card at 0.4×; opened, they
     *  leave it at 1.0. */
    @Test
    fun aChannelWhoseCardsAreIgnoredDrops() {
        val lists = mapOf(CHAPTERS_VIDEO_ID to chapters.take(2))
        fun veritasiumCards(openedAgo: Duration?): List<Impression> =
            (1..3).map { n -> shown("veritas%04d".format(n), count = 1, ago = 2.days, channelUrl = VERITASIUM, openedAgo = openedAgo) }

        val ignored = rank(listOf(chaptersSeed), lists, impressions = veritasiumCards(openedAgo = null))
        val opened = rank(listOf(chaptersSeed), lists, impressions = veritasiumCards(openedAgo = 1.days))

        assertEquals(listOf("twDvxNooI_4", "MiUHjLxm3V0"), ignored.ids())
        assertEquals(listOf("MiUHjLxm3V0", "twDvxNooI_4"), opened.ids())
    }

    @Test
    fun noKeptListsNoPicks_theSeedsStillGoToTheRefresher() {
        val ranking = rank(listOf(chaptersSeed), emptyMap())

        assertEquals(ForYouRanking(emptyList(), pinned = null, seeds = listOf(VideoId(CHAPTERS_VIDEO_ID))), ranking)
    }

    private fun rank(
        seeds: List<Seed>,
        lists: Map<String, List<VideoItem>>,
        history: List<WatchEntry> = emptyList(),
        subscribed: Set<String> = emptySet(),
        impressions: List<Impression> = emptyList(),
        hiddenVideos: Set<VideoId> = emptySet(),
        hiddenChannels: Set<String> = emptySet(),
        pinned: PinnedPick? = null,
    ): ForYouRanking = rankForYou(
        ForYouInputs(
            seeds = seeds,
            lists = lists.entries.associate { (id, items) -> VideoId(id) to relatedList(items) },
            history = history,
            subscribedChannels = subscribed,
            impressions = impressions,
            hiddenVideos = hiddenVideos,
            hiddenChannels = hiddenChannels,
            pinned = pinned,
        ),
        NOW,
    )
}
