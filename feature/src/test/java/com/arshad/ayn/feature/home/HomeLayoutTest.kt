package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.feature.foryou.ForYouPick
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.WatchMark
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val channel = Subscription("https://www.youtube.com/channel/UCa", "A", avatarUrl = "a.jpg", 0L, 0L)
    private val video = VideoItem(
        VideoId("aaaaaaaaaaa"), "t", "A", channel.channelUrl, 60, 1, null, now, null, isLive = false,
    )

    private val partWay = HistoryVideo(video, WatchMark.PartWay(10_000, 60_000), now)
    private val liveVideo = video.copy(id = VideoId("lllllllllll"), isLive = true)

    private fun ready(
        hero: Hero?,
        feedsFailed: Boolean = false,
        live: LiveNowState = LiveNowState.Ready(emptyList()),
        continueWatching: List<HistoryVideo> = emptyList(),
        inWatchLater: Boolean = false,
    ) = HomeUiState(
        subscriptions = SubscriptionsState.Ready(
            hero, emptyList(), listOf(ChannelCard(channel, null)), now, feedsFailed, hasUploads = hero != null,
        ),
        liveNow = live,
        attemptedAt = now,
        continueWatching = continueWatching,
        heroInWatchLater = inWatchLater,
    )

    @Test
    fun theHeroWearsItsChannelsAvatarAndEmptyRowsAreLeftOut() {
        val layout = homeLayout(ready(Hero(video, isNew = true))) as HomeLayout.Browse

        assertEquals("a.jpg", (layout.top as HomeTop.HeroTop).avatarUrl)
        assertEquals(listOf("channels"), layout.rows.map { it.key })
    }

    /** PAF-D5: while For you's first ranking is awaited, no subscription hero is drawn to give way. */
    @Test
    fun aHeldHeroKeepsTheSkeletonUntilForYouShowsOrTheHeroIsOff() {
        val held = ready(Hero(video, isNew = true)).copy(heroHeld = true)
        val pick = video.copy(id = VideoId("fffffffffff"))
        val reason = ForYouReason(VideoId("sssssssssss"), SeedKind.Watched, "a seed")

        assertEquals(HomeLayout.Loading, homeLayout(held))
        assertEquals(pick, ((homeLayout(held.copy(forYou = ForYouShown(Hero(pick, isNew = false), reason, emptyList()))) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video)
        assertEquals(HomeTop.HeroOff, (homeLayout(held, heroOn = false) as HomeLayout.Browse).top)
    }

    @Test
    fun forYousHeroTakesTheTopOverTheSubscriptions() {
        val pick = video.copy(id = VideoId("fffffffffff"))
        val reason = ForYouReason(VideoId("sssssssssss"), SeedKind.Watched, "a seed")
        val state = ready(Hero(video, isNew = true)).copy(forYou = ForYouShown(Hero(pick, isNew = false), reason, emptyList()))

        val layout = homeLayout(state) as HomeLayout.Browse

        assertEquals(pick, (layout.top as HomeTop.HeroTop).hero.video)
    }

    private val reason = ForYouReason(VideoId("sssssssssss"), SeedKind.Watched, "a seed")
    private val heroPick = video.copy(id = VideoId("fffffffffff"))
    private val rowPick = ForYouPick(video.copy(id = VideoId("ggggggggggg")), reason)
    private val forYou = ForYouShown(Hero(heroPick, isNew = false), reason, listOf(rowPick))

    @Test
    fun aSubscriptionsHeroWhosePageTheFilterRefusedIsLeftOutEvenBeforeTheFilterLoads() {
        val refused = ready(Hero(video, isNew = true, page = HeroPage.Filtered))

        val shown = refused.shownUnder(filter = null)

        assertEquals(null, (shown.subscriptions as SubscriptionsState.Ready).hero)
    }

    private fun HomeLayout.forYouIds(): List<String> =
        ((this as HomeLayout.Browse).rows.first { it is HomeRow.ForYou } as HomeRow.ForYou).picks.map { it.video.id.value }

    @Test
    fun forYousRowLeadsTheRowsUnderItsHeroFromTheSecondPick() {
        val layout = homeLayout(ready(null, continueWatching = listOf(partWay)).copy(forYou = forYou)) as HomeLayout.Browse

        assertEquals(reason, (layout.top as HomeTop.HeroTop).reason)
        assertEquals(listOf("foryou", "continue", "channels"), layout.rows.map { it.key })
        assertEquals(listOf("ggggggggggg"), layout.forYouIds())
    }

    @Test
    fun withTheHeroOffForYousRowStartsAtTheFirstPick() {
        val layout = homeLayout(ready(null).copy(forYou = forYou), heroOn = false)

        assertEquals(HomeTop.HeroOff, (layout as HomeLayout.Browse).top)
        assertEquals(listOf("fffffffffff", "ggggggggggg"), layout.forYouIds())
    }

    @Test
    fun underTheFailedFeedsStatementForYousRowStartsAtTheFirstPick() {
        assertEquals(listOf("fffffffffff", "ggggggggggg"), homeLayout(ready(null, feedsFailed = true).copy(forYou = forYou)).forYouIds())
    }

    @Test
    fun forYouShowsWhileTheFeedLoadsAndOnTheNoSubscriptionsSlate() {
        val loading = HomeUiState(attemptedAt = now, forYou = forYou)
        val noSubscriptions = HomeUiState(subscriptions = SubscriptionsState.NoSubscriptions, attemptedAt = now, forYou = forYou)

        assertEquals(heroPick, ((homeLayout(loading) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video)
        assertEquals(heroPick, ((homeLayout(noSubscriptions) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video)
        assertEquals(listOf("ggggggggggg"), homeLayout(noSubscriptions).forYouIds())
    }

    @Test
    fun homesErrorShowsWithoutForYou() {
        listOf(AccessFailure.OFFLINE, AccessFailure.UNREADABLE).forEach { failure ->
            val state = ready(null, live = LiveNowState.Failed(failure)).copy(forYou = forYou)

            assertEquals(HomeLayout.Failed(failure), homeLayout(state))
        }
    }

    @Test
    fun failedFeedsKeepTheTopOverForYousHero() {
        val reason = ForYouReason(VideoId("sssssssssss"), SeedKind.Watched, "a seed")
        val state = ready(hero = null, feedsFailed = true).copy(forYou = ForYouShown(Hero(video, isNew = false), reason, emptyList()))

        val layout = homeLayout(state) as HomeLayout.Browse

        assertEquals(HomeTop.FeedsFailed(channelCount = 1), layout.top)
    }

    @Test
    fun withTheHeroOffTheHerosVideoLeadsNewFromYourSubscriptions() {
        val layout = homeLayout(ready(Hero(video, isNew = true)), heroOn = false) as HomeLayout.Browse

        assertEquals(HomeTop.HeroOff, layout.top)
        assertEquals(listOf("new", "channels"), layout.rows.map { it.key })
        assertEquals(listOf(video), (layout.rows.first() as HomeRow.Videos).videos)
    }

    @Test
    fun withTheHeroOffContinueWatchingStillLeadsTheRows() {
        val layout = homeLayout(ready(Hero(video, isNew = true), continueWatching = listOf(partWay)), heroOn = false) as HomeLayout.Browse

        assertEquals(listOf("continue", "new", "channels"), layout.rows.map { it.key })
    }

    @Test
    fun withTheHeroOffAndNothingUnwatchedTheRowsStillStartAtTheTop() {
        val layout = homeLayout(ready(hero = null), heroOn = false) as HomeLayout.Browse

        assertEquals(HomeTop.HeroOff, layout.top)
        assertEquals(listOf("channels"), layout.rows.map { it.key })
    }

    @Test
    fun withTheHeroOffFailedFeedsStillSaySo() {
        assertEquals(HomeTop.FeedsFailed(1), (homeLayout(ready(null, feedsFailed = true), heroOn = false) as HomeLayout.Browse).top)
    }

    @Test
    fun withTheHeroOffANoSubscriptionsStatementStillShows() {
        val blank = HomeUiState(subscriptions = SubscriptionsState.NoSubscriptions, attemptedAt = now)

        assertEquals(HomeTop.Blank, (homeLayout(blank, heroOn = false) as HomeLayout.Browse).top)
    }

    @Test
    fun anUploadsTabHeroFindsItsChannelsAvatarUnderTheChannelUrl() {
        val bijan = "https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg"
        val channels = listOf(ChannelCard(Subscription(bijan, "Bijan Bowen", avatarUrl = "b.jpg", 0L, 0L), null))

        assertEquals("b.jpg", heroAvatar("$bijan/videos", channels))
        assertEquals(null, heroAvatar(null, channels))
    }

    @Test
    fun feedsWithNoVideoAndNoFailureShowTheRowsAlone() {
        val layout = homeLayout(ready(hero = null)) as HomeLayout.Browse

        assertEquals(HomeTop.None, layout.top)
    }

    @Test
    fun continueWatchingLeadsTheRowsUnderTheHeroAndTheHeroSaysWhetherItIsInWatchLater() {
        val layout = homeLayout(
            ready(Hero(video, isNew = true), live = LiveNowState.Ready(listOf(liveVideo)), continueWatching = listOf(partWay), inWatchLater = true),
        ) as HomeLayout.Browse

        assertEquals(listOf("continue", "live", "channels"), layout.rows.map { it.key })
        assertEquals(true, (layout.top as HomeTop.HeroTop).inWatchLater)
    }

    @Test
    fun continueWatchingLeadsTheBlankSlatesAndTheCompactStatementsRows() {
        val blank = HomeUiState(
            subscriptions = SubscriptionsState.NoSubscriptions,
            liveNow = LiveNowState.Ready(listOf(liveVideo)),
            attemptedAt = now,
            continueWatching = listOf(partWay),
        )
        assertEquals(listOf("continue", "live"), (homeLayout(blank) as HomeLayout.Browse).rows.map { it.key })

        val failedFeeds = homeLayout(ready(null, feedsFailed = true, continueWatching = listOf(partWay))) as HomeLayout.Browse
        assertEquals(HomeTop.FeedsFailed(1), failedFeeds.top)
        assertEquals(listOf("continue", "channels"), failedFeeds.rows.map { it.key })
    }

    @Test
    fun everythingFailingKeepsTheErrorStatementWhateverContinueWatchingHolds() {
        val state = ready(null, feedsFailed = true, live = LiveNowState.Failed(AccessFailure.UNREADABLE), continueWatching = listOf(partWay))

        assertEquals(HomeLayout.Failed(AccessFailure.UNREADABLE), homeLayout(state))
    }

    @Test
    fun everyFeedFailingIsTheCompactStatementUntilLiveNowFailsToo() {
        assertEquals(HomeTop.FeedsFailed(1), (homeLayout(ready(null, feedsFailed = true)) as HomeLayout.Browse).top)
        assertEquals(
            HomeLayout.Failed(AccessFailure.OFFLINE),
            homeLayout(ready(null, feedsFailed = true, live = LiveNowState.Failed(AccessFailure.OFFLINE))),
        )
    }

    /** `kp-kids` kidhome: no hero, the subscriptions named as the kid's channels, the owner's picks after them. */
    @Test
    fun aKidsHomeIsItsRowsFromTheTopWithTheOwnersPicks() {
        val pick = video.copy(id = VideoId("ppppppppppp"))
        val state = HomeUiState(
            subscriptions = SubscriptionsState.Ready(null, listOf(video), listOf(ChannelCard(channel, null)), now, feedsFailed = false, hasUploads = true),
            liveNow = LiveNowState.Ready(emptyList()),
            attemptedAt = now,
            continueWatching = listOf(partWay),
            kids = KidsPicks("Arshad", listOf(pick)),
        )

        val layout = homeLayout(state) as HomeLayout.Browse

        assertEquals(HomeTop.HeroOff, layout.top)
        assertEquals(listOf("continue", "new", "picked", "channels"), layout.rows.map { it.key })
        assertEquals("New from your channels", (layout.rows[1] as HomeRow.Videos).title)
        assertEquals("Picked for you by Arshad", (layout.rows[2] as HomeRow.Videos).title)
    }

    @Test
    fun aKidsProfileWithNothingApprovedSaysSo() {
        val nothing = HomeUiState(
            subscriptions = SubscriptionsState.NoSubscriptions,
            liveNow = LiveNowState.Ready(emptyList()),
            attemptedAt = now,
            kids = KidsPicks.None,
        )
        assertEquals(HomeLayout.Browse(HomeTop.NothingApproved, emptyList()), homeLayout(nothing))

        val picksOnly = homeLayout(nothing.copy(kids = KidsPicks(null, listOf(video)))) as HomeLayout.Browse
        assertEquals(HomeTop.HeroOff, picksOnly.top)
        assertEquals("Picked for you", (picksOnly.rows.single() as HomeRow.Videos).title)
    }
}
