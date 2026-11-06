package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.lock.OwnerPass
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Filters over a real in-memory Room 3 database (KP-D12, D18): the two setters kept apart, the
 *  write-time checks and caps, lenient fail-closed reads, and records going with their profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val store = FilterRepository(database, clock)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aProfileWithNothingStoredHasNoRestrictionsAndNoRules() = scope.runTest {
        val owner = profiles.ensureOwner().id

        assertEquals(ProfileRules(), store.rules(owner).first())
    }

    @Test
    fun theProfilesOwnRulesAndTheOwnersAreKeptApart() = scope.runTest {
        val mira = mira()

        assertEquals(RuleEdit.Added, store.addOwn(mira, Keyword("prank")))
        clock.nowMillis = T0 + 1
        assertEquals(RuleEdit.Added, store.addRestriction(OwnerPass(), mira, Keyword("prank")))

        assertEquals(
            listOf(StoredRule(Keyword("prank"), byOwner = false, T0), StoredRule(Keyword("prank"), byOwner = true, T0 + 1)),
            store.rules(mira).first().rules,
        )
        assertTrue(store.removeOwn(mira, Keyword("prank")))
        assertEquals(listOf(StoredRule(Keyword("prank"), byOwner = true, T0 + 1)), store.rules(mira).first().rules)
    }

    @Test
    fun aRuleAddedTwiceKeepsItsFirstTime() = scope.runTest {
        val mira = mira()
        store.addOwn(mira, BlockedVideo(VIDEO, "Title", null))
        clock.nowMillis = T0 + 5

        assertEquals(RuleEdit.AlreadyThere, store.addOwn(mira, BlockedVideo(VIDEO, "Other", null)))
        assertEquals(listOf(StoredRule(BlockedVideo(VIDEO, "Title", null), byOwner = false, T0)), store.rules(mira).first().rules)
    }

    @Test
    fun aChannelIsKeptByItsCanonicalUrlSoTwoFormsAreOneRule() = scope.runTest {
        val mira = mira()

        store.addOwn(mira, BlockedChannel("$CHANNEL/videos", " fern "))

        assertEquals(RuleEdit.AlreadyThere, store.addOwn(mira, BlockedChannel(CHANNEL, "fern")))
        assertEquals(listOf(BlockedChannel(CHANNEL, "fern")), store.rules(mira).first().rules.map { it.rule })
        assertTrue(store.removeOwn(mira, BlockedChannel("$CHANNEL/videos", null)))
    }

    @Test
    fun rulesTheStoreCantUseAreInvalidAndNothingIsWritten() = scope.runTest {
        val mira = mira()

        assertEquals(RuleEdit.Invalid, store.addOwn(mira, Keyword("   ")))
        assertEquals(RuleEdit.Invalid, store.addOwn(mira, Keyword("x".repeat(MAX_KEYWORD_LENGTH + 1))))
        assertEquals(RuleEdit.Invalid, store.addOwn(mira, BlockedVideo("short", null, null)))
        assertEquals(RuleEdit.Invalid, store.addOwn(mira, BlockedChannel(" ", null)))
        assertEquals(RuleEdit.Invalid, store.addRestriction(OwnerPass(), mira, Pattern("y".repeat(MAX_PATTERN_LENGTH + 1))))
        assertEquals(RuleEdit.Invalid, store.addRestriction(OwnerPass(), mira, ApprovedVideo("bad", "T", null, null, null, null)))

        assertEquals(emptyList<StoredRule>(), store.rules(mira).first().rules)
    }

    @Test
    fun aKeywordIsStoredTrimmed() = scope.runTest {
        val mira = mira()

        store.addOwn(mira, Keyword("  mukbang "))

        assertEquals(listOf(Keyword("mukbang")), store.rules(mira).first().rules.map { it.rule })
    }

    @Test
    fun theFiftyFirstPatternIsRefusedWhileOtherKindsStillAdd() = scope.runTest {
        val mira = mira()
        repeat(MAX_PATTERNS) { store.addRestriction(OwnerPass(), mira, Pattern("p$it")) }

        assertEquals(RuleEdit.Full, store.addRestriction(OwnerPass(), mira, Pattern("one more")))
        assertEquals(RuleEdit.Added, store.addRestriction(OwnerPass(), mira, Keyword("other")))
        assertEquals(MAX_PATTERNS, store.rules(mira).first().rules.count { it.rule is Pattern })
    }

    @Test
    fun anApprovedVideoKeepsWhatAKidsHomeShows() = scope.runTest {
        val kid = mira()
        val video = ApprovedVideo(VIDEO, "Extraordinary Wildlife Of The Arctic", "BBC Earth", "$CHANNEL/videos", "https://i.ytimg.com/a.jpg", 1_943)

        store.addRestriction(OwnerPass(), kid, video)

        assertEquals(listOf(video.copy(channelUrl = CHANNEL)), store.rules(kid).first().rules.map { it.rule })
    }

    @Test
    fun restrictionsAreStoredWithTheirTimeAndReadBack() = scope.runTest {
        val mira = mira()
        val wanted = ProfileRestrictions(
            restrictedMode = RestrictedMode.Strict,
            hideShorts = true,
            lengthLimits = LengthLimits(maxMinutes = 30),
            dailyLimitMinutes = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.SATURDAY to 90),
            bedtime = Bedtime(startMinute = 19 * 60 + 30, endMinute = 7 * 60),
        )

        assertTrue(store.updateRestrictions(OwnerPass(), mira) { wanted })

        assertEquals(wanted, store.rules(mira).first().restrictions)
        assertEquals(T0, database.filterDao().restrictionsRow(mira)?.updatedAtMillis)
    }

    /** KP-D18: a stored level this build doesn't know fails closed; values out of range read unset. */
    @Test
    fun unknownOrOutOfRangeStoredRestrictionsReadStrictAndUnset() = scope.runTest {
        val mira = mira()
        database.filterDao().upsertRestrictions(
            ProfileRestrictions().toEntity(mira, T0).copy(
                restrictedMode = "moderate",
                minLengthMinutes = 30,
                maxLengthMinutes = 10,
                limitMonday = MINUTES_PER_DAY + 1,
                limitTuesday = 45,
                bedtimeStartMinute = 600,
                bedtimeEndMinute = 600,
            ),
        )

        assertEquals(
            ProfileRestrictions(restrictedMode = RestrictedMode.Strict, dailyLimitMinutes = mapOf(DayOfWeek.TUESDAY to 45)),
            store.rules(mira).first().restrictions,
        )
    }

    /** FPK-D16: the owner's packs, options and Islamic switches are stored with the other
     *  restrictions. */
    @Test
    fun theOwnersFilterPacksAreStoredAndReadBack() = scope.runTest {
        val mira = mira()
        val packs = FilterPacks(
            harmfulForKids = true,
            overstimulating = true,
            islamicOnly = true,
            islamicChannels = mapOf(ALAFASY to false, TWO_HOLY_MOSQUES to true),
        )

        assertTrue(store.updateRestrictions(OwnerPass(), mira) { it.copy(hideLive = true, filterPacks = packs) })

        assertEquals(ProfileRestrictions(hideLive = true, filterPacks = packs), store.rules(mira).first().restrictions)
    }

    /** FPK-D18: no switches are stored as NULL, not as an empty object. */
    @Test
    fun noIslamicSwitchesAreStoredAsNull() = scope.runTest {
        val mira = mira()

        store.updateRestrictions(OwnerPass(), mira) { it.copy(filterPacks = FilterPacks(islamicOnly = true)) }

        assertEquals(null, database.filterDao().restrictionsRow(mira)?.filterPacks?.islamicChannels)
    }

    /** FPK-D18: switch text that doesn't read as an object of switches reads as none. */
    @Test
    fun storedIslamicSwitchesThatDontReadAreNone() = scope.runTest {
        val mira = mira()

        val read = listOf("""{"$ALAFASY":"off"}""", "not json", "[]").map { text ->
            storeIslamicSwitches(mira, text)
            store.rules(mira).first().restrictions.filterPacks
        }

        assertEquals(List(3) { FilterPacks(islamicOnly = true) }, read)
    }

    /** FPK-D18: a channel the list no longer has is left out; the rest read. */
    @Test
    fun storedSwitchesForChannelsTheListDoesntHaveAreLeftOut() = scope.runTest {
        val mira = mira()

        storeIslamicSwitches(mira, """{"$ALAFASY":false,"UCgoneeeeeeeeeeeeeeeeeee":true}""")

        assertEquals(
            FilterPacks(islamicOnly = true, islamicChannels = mapOf(ALAFASY to false)),
            store.rules(mira).first().restrictions.filterPacks,
        )
    }

    /** FPK-D1, D19: a new kids profile starts with Harmful for kids on as the owner's restriction,
     *  and a later restriction edit (Kids add's time rules) keeps it. */
    @Test
    fun aNewKidsProfileStartsWithHarmfulForKidsThatLaterEditsKeep() = scope.runTest {
        val kid = kid()
        val start = ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true))
        assertEquals(start, store.rules(kid).first().restrictions)

        store.updateRestrictions(OwnerPass(), kid) { it.copy(dailyLimitMinutes = mapOf(DayOfWeek.MONDAY to 60)) }

        assertEquals(start.copy(dailyLimitMinutes = mapOf(DayOfWeek.MONDAY to 60)), store.rules(kid).first().restrictions)
    }

    /** Islamic content only on as the owner's restriction, its switches stored as [text]. */
    private suspend fun storeIslamicSwitches(profileId: Long, text: String) {
        val row = ProfileRestrictions(filterPacks = FilterPacks(islamicOnly = true)).toEntity(profileId, T0)
        database.filterDao().upsertRestrictions(row.copy(filterPacks = row.filterPacks.copy(islamicChannels = text)))
    }

    @Test
    fun aRuleOfAKindThisBuildDoesntKnowIsLeftOutOfTheRules() = scope.runTest {
        val mira = mira()
        database.filterDao().insertRule(ContentRuleEntity(mira, true, "topic", "news", null, null, null, null, null, T0))
        store.addOwn(mira, Keyword("prank"))

        assertEquals(listOf(Keyword("prank")), store.rules(mira).first().rules.map { it.rule })
    }

    @Test
    fun writesForARemovedProfileFailAndRemovingItTakesItsFilters() = scope.runTest {
        val mira = mira()
        store.addOwn(mira, Keyword("prank"))
        store.updateRestrictions(OwnerPass(), mira) { it.copy(hideLive = true) }

        assertTrue(profiles.remove(OwnerPass(), mira))

        assertEquals(ProfileRules(), store.rules(mira).first())
        assertEquals(RuleEdit.Failed, store.addOwn(mira, Keyword("again")))
        assertFalse(store.updateRestrictions(OwnerPass(), mira) { it.copy(hideLive = true) })
    }

    /** KP-D10: a kids profile's subscriptions are its approved channels; another profile's
     *  subscriptions approve nothing. */
    @Test
    fun aKidsProfilesSubscriptionsAreItsApprovedChannels() = scope.runTest {
        val mira = mira()
        val kid = kid()
        database.subscriptionDao().upsert(SubscriptionEntity(kid, CHANNEL, "V", null, T0, T0))
        database.subscriptionDao().upsert(SubscriptionEntity(mira, CHANNEL, "V", null, T0, T0))

        val rules = store.rules(kid).first()
        assertTrue(rules.kids)
        assertEquals(setOf(CHANNEL), rules.approvedChannels)
        assertEquals(ProfileRules(), store.rules(mira).first())
    }

    /** KP-D10: the owner approves a channel by the kid's id, from any session; any URL form of it
     *  is the one channel. */
    @Test
    fun anApprovedChannelJoinsTheKidsApprovedChannelsUntilTheOwnerRemovesIt() = scope.runTest {
        val kid = kid()

        assertTrue(store.approveChannel(OwnerPass(), kid, channel("$CHANNEL/videos")))

        assertEquals(listOf(Subscription(CHANNEL, "Veritasium", "https://yt3.ggpht.com/v", T0, T0)), store.approvedChannels(kid).first())
        assertEquals(setOf(CHANNEL), store.rules(kid).first().approvedChannels)
        assertTrue(store.removeApprovedChannel(OwnerPass(), kid, "$CHANNEL/streams"))
        assertEquals(emptyList<Subscription>(), store.approvedChannels(kid).first())
        assertFalse(store.removeApprovedChannel(OwnerPass(), kid, CHANNEL))
    }

    /** Approving is for kids profiles only: a standard profile's subscriptions are its own. */
    @Test
    fun approvingOnAStandardProfileChangesNothing() = scope.runTest {
        val mira = mira()
        database.subscriptionDao().upsert(SubscriptionEntity(mira, CHANNEL, "V", null, T0, T0))

        assertFalse(store.approveChannel(OwnerPass(), mira, channel(OTHER_CHANNEL)))
        assertFalse(store.removeApprovedChannel(OwnerPass(), mira, CHANNEL))

        assertEquals(emptyList<Subscription>(), store.approvedChannels(mira).first())
        assertEquals(listOf(CHANNEL), database.subscriptionDao().all().map { it.channelUrl })
    }

    private fun channel(url: String): ChannelItem = ChannelItem(
        name = "Veritasium",
        url = url,
        avatarUrl = "https://yt3.ggpht.com/v",
        subscriberCount = -1,
        description = null,
        verified = false,
    )

    private suspend fun kid(): Long {
        val owner = profiles.ensureOwner().id
        database.profileDao().setLock(owner, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        return (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
    }

    private suspend fun mira(): Long {
        profiles.ensureOwner()
        return (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
    }
}

private const val T0 = 1_791_500_000_000L
private const val VIDEO = "JsBZOcqZerk"
private const val CHANNEL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
private const val OTHER_CHANNEL = "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q"

/** Two of Islamic content only's channels (`IslamicOnly`): on by default, and off. */
private const val ALAFASY = "UCmMcOjsVehVlEOteyrhjI2Q"
private const val TWO_HOLY_MOSQUES = "UCdAnnrdcG0t8x1qDjVgaIfQ"
