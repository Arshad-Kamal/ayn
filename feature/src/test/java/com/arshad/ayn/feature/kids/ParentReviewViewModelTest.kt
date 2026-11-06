package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeKidsReviewKeeps
import com.arshad.ayn.core.store.FakeKidsTimeStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.library.watchEntry
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The parent review (F12, Q17) over in-memory stores and the real [SubscriptionsFeed] on a fake
 * channel feed: the week against the limits, today's history, and the new uploads with Keep and
 * Block. Today is Saturday 2026-10-10 by YouTube's clock, in UTC.
 */
class ParentReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = ProfileSession().apply { select(KID) }
    private val filters = FakeFilterStore()
    private val kidsTime = FakeKidsTimeStore()
    private val watchStore = FakeWatchStore()
    private val subscriptions = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(CHANNEL)) }
    private val feedSource = FakeFeedExtractor()
    private val keeps = FakeKidsReviewKeeps()
    private val serverClock = ServerClock(TestTimeSource()).apply { record(NOW) }
    private val owner: OwnerPass = runBlocking { FakeProfileLocks().ownerPassWithoutLock()!! }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theWeekRunsMondayToSundayWithEachDaysLimitAndMoreTime() = runTest(dispatcher) {
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val limits = weekdays.associateWith { 60 } + (DayOfWeek.SATURDAY to 90)
        filters.rulesById.value = mapOf(KID to ProfileRules(ProfileRestrictions(dailyLimitMinutes = limits), kids = true))
        kidsTime.daysById.value = mapOf(
            KID to mapOf(
                LocalDate.of(2026, 10, 8) to DayTime(usedSeconds = 75 * 60L, moreTimeMinutes = 15),
                LocalDate.of(2026, 10, 10) to DayTime(usedSeconds = 42 * 60L),
                LocalDate.of(2026, 10, 4) to DayTime(usedSeconds = 600), // last week's Sunday
            ),
        )

        val week = review().uiState.value.week

        assertEquals((5..11).map { LocalDate.of(2026, 10, it) }, week.map { it.date })
        assertEquals(ReviewDay(LocalDate.of(2026, 10, 8), 75 * 60L, 60, 15, isToday = false), week[3])
        assertEquals(ReviewDay(LocalDate.of(2026, 10, 10), 42 * 60L, 90, 0, isToday = true), week[5])
        assertEquals(ReviewDay(LocalDate.of(2026, 10, 11), 0, null, 0, isToday = false), week[6])
    }

    @Test
    fun beforeYouTubesClockIsKnownTheDevicesNamesTheWeek() = runTest(dispatcher) {
        val review = ParentReviewViewModel(
            session, filters, kidsTime, watchStore, subscriptions, feed(), keeps,
            clock = ServerClock(TestTimeSource()),
            deviceClock = TestClock(Instant.parse("2026-10-14T09:00:00Z")),
            zone = ZoneOffset.UTC,
        )
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 10, 12), review.uiState.value.week.first().date)
    }

    @Test
    fun watchedTodayListsOnlyTodaysHistory() = runTest(dispatcher) {
        watchStore.entries.value = listOf(
            watchEntry("bluey000001", lastWatchedAtMillis = (NOW - 1.hours).toEpochMilliseconds()),
            watchEntry("msrachel001", lastWatchedAtMillis = (NOW - 1.days).toEpochMilliseconds()),
        )

        val watched = review().uiState.value.watchedToday

        assertEquals(listOf("bluey000001"), watched.map { it.video.id.value })
    }

    @Test
    fun newUploadsAreTheLastSevenDaysNotYetKept() = runTest(dispatcher) {
        keeps.keptById[KID] = mutableSetOf(KEPT.id.value)
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH, KEPT, OLD, UNDATED), next = null)

        val uploads = review().uiState.value.uploads

        assertEquals(ReviewUploads.Ready(listOf(FRESH), failedChannels = 0), uploads)
    }

    @Test
    fun keepRemembersTheVideoAndTakesItOffTheList() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        val review = review()

        review.onKeep(owner, FRESH)
        advanceUntilIdle()

        assertEquals(setOf(FRESH.id.value), keeps.kept(KID))
        assertEquals(ReviewUploads.Ready(emptyList(), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aKeepThatIsntWrittenLeavesTheVideoListed() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        keeps.failWrites = true
        val review = review()

        review.onKeep(owner, FRESH)
        advanceUntilIdle()

        assertEquals(ReviewUploads.Ready(listOf(FRESH), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun blockAddsAnOwnerRuleOnTheKidAndTakesTheVideoOffTheList() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        val review = review()

        review.onBlock(owner, FRESH)
        advanceUntilIdle()

        val blocked = BlockedVideo(FRESH.id.value, FRESH.title, FRESH.uploaderUrl)
        assertEquals(listOf(StoredRule(blocked, byOwner = true, addedAtMillis = 0)), filters.rulesById.value.getValue(KID).rules)
        assertEquals(ReviewUploads.Ready(emptyList(), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aBlockShowsItsNoticeAndUndoTakesTheRuleBackAndReturnsTheVideoInUploadOrder() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH, MIDDLE, OLDEST), next = null)
        val review = review()

        review.onBlock(owner, MIDDLE)
        advanceUntilIdle()
        assertEquals(MIDDLE, review.uiState.value.blocked)
        review.onKeep(owner, FRESH)
        review.onUndoBlock(owner)
        advanceUntilIdle()

        assertNull(review.uiState.value.blocked)
        assertTrue(filters.rulesById.value.getValue(KID).rules.isEmpty())
        assertEquals(ReviewUploads.Ready(listOf(MIDDLE, OLDEST), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aSecondBlockTakesOverTheNoticeAndUndoTakesBackOnlyIt() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH, MIDDLE, OLDEST), next = null)
        val review = review()

        review.onBlock(owner, FRESH)
        review.onBlock(owner, OLDEST)
        advanceUntilIdle()
        review.onUndoBlock(owner)
        advanceUntilIdle()

        assertEquals(listOf(blockedRule(FRESH)), filters.rulesById.value.getValue(KID).rules.map { it.rule })
        assertEquals(ReviewUploads.Ready(listOf(MIDDLE, OLDEST), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aBlockWhoseNoticeClosesStands() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        val review = review()
        review.onBlock(owner, FRESH)
        advanceUntilIdle()

        review.onBlockNoticeClosed()
        review.onUndoBlock(owner)
        advanceUntilIdle()

        assertEquals(listOf(blockedRule(FRESH)), filters.rulesById.value.getValue(KID).rules.map { it.rule })
        assertEquals(ReviewUploads.Ready(emptyList(), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aBlockAlreadyThereLeavesTheListWithNoNotice() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        filters.addRestriction(owner, KID, blockedRule(FRESH))
        val review = review()

        review.onBlock(owner, FRESH)
        advanceUntilIdle()

        assertNull(review.uiState.value.blocked)
        assertEquals(ReviewUploads.Ready(emptyList(), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun anUndoThatIsntWrittenLeavesTheBlockStanding() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        val review = review()
        review.onBlock(owner, FRESH)
        advanceUntilIdle()

        filters.failWrites = true
        review.onUndoBlock(owner)
        advanceUntilIdle()

        assertNull(review.uiState.value.blocked)
        assertEquals(listOf(blockedRule(FRESH)), filters.rulesById.value.getValue(KID).rules.map { it.rule })
        assertEquals(ReviewUploads.Ready(emptyList(), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun aBlockThatIsntWrittenLeavesTheVideoListedWithNoNotice() = runTest(dispatcher) {
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        filters.failWrites = true
        val review = review()

        review.onBlock(owner, FRESH)
        advanceUntilIdle()

        assertNull(review.uiState.value.blocked)
        assertEquals(ReviewUploads.Ready(listOf(FRESH), failedChannels = 0), review.uiState.value.uploads)
    }

    private fun blockedRule(video: VideoItem): BlockedVideo = BlockedVideo(video.id.value, video.title, video.uploaderUrl)

    @Test
    fun everyChannelFailingFailsTheUploadsAndRetryReadsAgain() = runTest(dispatcher) {
        feedSource.failingChannels += CHANNEL
        val review = review()
        assertEquals(ReviewUploads.Failed, review.uiState.value.uploads)

        feedSource.failingChannels.clear()
        feedSource.feeds[CHANNEL] = ItemsPage(items = listOf(FRESH), next = null)
        review.retryUploads()
        advanceUntilIdle()

        assertEquals(ReviewUploads.Ready(listOf(FRESH), failedChannels = 0), review.uiState.value.uploads)
    }

    @Test
    fun noApprovedChannelIsAnEmptyListNotAFailure() = runTest(dispatcher) {
        subscriptions.rows.value = emptyList()

        val uploads = review().uiState.value.uploads

        assertTrue(uploads is ReviewUploads.Ready && uploads.videos.isEmpty())
    }

    private fun feed(): SubscriptionsFeed = SubscriptionsFeed(feedSource, clock = TestClock(NOW))

    private fun TestScope.review(): ParentReviewViewModel = ParentReviewViewModel(
        session, filters, kidsTime, watchStore, subscriptions, feed(), keeps,
        clock = serverClock,
        zone = ZoneOffset.UTC,
    ).also { advanceUntilIdle() }

    private companion object {
        const val KID = 4L
        val NOW: Instant = Instant.parse("2026-10-10T15:00:00Z")
        val FRESH: VideoItem = testVideo("fresh000001").copy(uploadDate = NOW - 2.days)
        val KEPT: VideoItem = testVideo("kept0000001").copy(uploadDate = NOW - 3.days, uploaderUrl = FRESH.uploaderUrl)
        val OLD: VideoItem = testVideo("old00000001").copy(uploadDate = NOW - 8.days, uploaderUrl = FRESH.uploaderUrl)
        val MIDDLE: VideoItem = testVideo("middle00001").copy(uploadDate = NOW - 4.days, uploaderUrl = FRESH.uploaderUrl)
        val OLDEST: VideoItem = testVideo("oldest00001").copy(uploadDate = NOW - 6.days, uploaderUrl = FRESH.uploaderUrl)
        val UNDATED: VideoItem = testVideo("undated0001").copy(uploaderUrl = FRESH.uploaderUrl)
        val CHANNEL: String = FRESH.uploaderUrl!!
    }
}
