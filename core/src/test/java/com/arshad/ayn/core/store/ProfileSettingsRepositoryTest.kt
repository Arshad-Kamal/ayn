package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Per-profile settings over a real in-memory Room 3 database (M7-D9): defaults without a row,
 *  writes on the given profile, lenient reads, and a write for a removed profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileSettingsRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val session = ProfileSession()
    private val profiles = ProfileRepository(database, clock)
    private val store = ProfileSettingsRepository(database, session, clock)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aProfileWithoutARowHasTheMockupDefaultsAndWritesNothing() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)

        val state = store.settings().first()

        assertEquals(ProfileSettingsState(owner, MOCKUP_DEFAULTS), state)
        assertNull(database.profileSettingsDao().row(owner))
    }

    /** M6-D6: a phone's store shows Shorts until a profile writes otherwise; a first write keeps
     *  the device's defaults for every value it doesn't change. */
    @Test
    fun aDevicesDefaultsAreReadAndAFirstWriteStartsFromThem() = scope.runTest {
        val phone = ProfileSettingsRepository(database, session, clock, defaults = MOCKUP_DEFAULTS.copy(showShorts = true))
        val owner = profiles.ensureOwner().id
        session.select(owner)

        assertTrue(phone.settings().first().settings.showShorts)
        phone.update(owner) { it.copy(speedPercent = 125) }

        assertEquals(MOCKUP_DEFAULTS.copy(showShorts = true, speedPercent = 125), phone.settings().first().settings)
        assertTrue("once stored, the row wins over any device's defaults", store.settings().first().settings.showShorts)
    }

    @Test
    fun anUpdateIsStoredWithItsTimeAndReadBack() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)

        val written = store.update(owner) { it.copy(speedPercent = 150, captionsLanguage = "pt-BR", showShorts = true) }

        assertTrue(written)
        assertEquals(
            MOCKUP_DEFAULTS.copy(speedPercent = 150, captionsLanguage = "pt-BR", showShorts = true),
            store.settings().first().settings,
        )
        assertEquals(T0, database.profileSettingsDao().row(owner)?.updatedAtMillis)
    }

    @Test
    fun aSecondUpdateStartsFromTheFirstsResult() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        store.update(owner) { it.copy(dislikes = false) }

        store.update(owner) { it.withAction(SegmentCategory.Filler, SegmentAction.Skip) }

        val settings = store.settings().first().settings
        assertEquals(false to SegmentAction.Skip, settings.dislikes to settings.segmentActions[SegmentCategory.Filler])
    }

    /** FY-D10's switch is stored with the rest, so a later write of another setting keeps it. */
    @Test
    fun forYouOffSurvivesALaterUpdate() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        store.update(owner) { it.copy(forYou = false) }

        store.update(owner) { it.copy(speedPercent = 125) }

        assertEquals(MOCKUP_DEFAULTS.copy(forYou = false, speedPercent = 125), store.settings().first().settings)
    }

    /** FPK-D16: the profile's own packs, options and Islamic switches are stored with the rest. */
    @Test
    fun filterPacksAreStoredAndSurviveALaterUpdate() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        val packs = FilterPacks(
            halalOnly = true,
            lootBoxes = true,
            islamicChannels = mapOf(ALAFASY to false, TWO_HOLY_MOSQUES to true),
        )
        store.update(owner) { it.copy(filterPacks = packs) }

        store.update(owner) { it.copy(speedPercent = 125) }

        assertEquals(MOCKUP_DEFAULTS.copy(filterPacks = packs, speedPercent = 125), store.settings().first().settings)
    }

    /** LIVE-D21: where chat shows is stored under its key with the rest. */
    @Test
    fun chatPlacementIsStoredUnderItsKeyAndSurvivesALaterUpdate() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        store.update(owner) { it.copy(chatPlacement = ChatPlacement.BottomLeft) }

        store.update(owner) { it.copy(speedPercent = 125) }

        assertEquals("bottom-left", database.profileSettingsDao().row(owner)?.chatPlacement)
        assertEquals(MOCKUP_DEFAULTS.copy(chatPlacement = ChatPlacement.BottomLeft, speedPercent = 125), store.settings().first().settings)
    }

    /** The keys rows and backups hold: renaming one strands them. */
    @Test
    fun eachChatPlacementKeepsItsStoredKey() {
        assertEquals(
            listOf("beside", "side", "top-left", "top-right", "bottom-left", "bottom-right"),
            ChatPlacement.entries.map { it.key },
        )
    }

    @Test
    fun theFlowFollowsUpdatesAndProfileSwitches() = scope.runTest {
        val owner = profiles.ensureOwner().id
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        session.select(owner)
        val seen = mutableListOf<ProfileSettingsState>()
        launch { store.settings().take(3).toList(seen) }
        advanceUntilIdle()

        store.update(owner) { it.copy(resume = false) }
        advanceUntilIdle()
        session.select(mira)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ProfileSettingsState(owner, MOCKUP_DEFAULTS),
                ProfileSettingsState(owner, MOCKUP_DEFAULTS.copy(resume = false)),
                ProfileSettingsState(mira, MOCKUP_DEFAULTS),
            ),
            seen,
        )
    }

    @Test
    fun anUpdateWritesTheProfileItNamesNotTheSelectedOne() = scope.runTest {
        val owner = profiles.ensureOwner().id
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        session.select(owner)

        store.update(mira) { it.copy(sponsorBlock = false) }

        assertEquals(MOCKUP_DEFAULTS, store.settings().first().settings)
        session.select(mira)
        assertFalse(store.settings().first().settings.sponsorBlock)
    }

    @Test
    fun anUpdateForARemovedProfileFailsAndWritesNothing() = scope.runTest {
        val owner = profiles.ensureOwner().id
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        profiles.remove(OwnerPass(), mira)

        assertFalse(store.update(mira) { it.copy(showShorts = true) })

        assertEquals(emptyList<ProfileSettingsEntity>(), database.profileSettingsDao().all())
        session.select(owner)
        assertEquals(MOCKUP_DEFAULTS, store.settings().first().settings)
    }

    @Test
    fun removingAProfileRemovesItsSettings() = scope.runTest {
        profiles.ensureOwner()
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        store.update(mira) { it.copy(showShorts = true) }

        profiles.remove(OwnerPass(), mira)

        assertEquals(emptyList<ProfileSettingsEntity>(), database.profileSettingsDao().all())
    }

    /** Each category is written to and read from its own column. */
    @Test
    fun eachCategorysActionIsStoredInItsOwnColumn() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        val chosen = mapOf(
            SegmentCategory.Sponsor to SegmentAction.Skip,
            SegmentCategory.SelfPromo to SegmentAction.Bar,
            SegmentCategory.Interaction to SegmentAction.Off,
            SegmentCategory.Intro to SegmentAction.Skip,
            SegmentCategory.Outro to SegmentAction.Bar,
            SegmentCategory.Preview to SegmentAction.Off,
            SegmentCategory.Hook to SegmentAction.Button,
            SegmentCategory.MusicOffTopic to SegmentAction.Skip,
            SegmentCategory.Filler to SegmentAction.Bar,
        )

        store.update(owner) { it.copy(segmentActions = chosen) }

        val row = checkNotNull(database.profileSettingsDao().row(owner))
        assertEquals(
            listOf("skip", "bar", "off", "skip", "bar", "off", "button", "skip", "bar"),
            with(row) { listOf(sbSponsor, sbSelfPromo, sbInteraction, sbIntro, sbOutro, sbPreview, sbHook, sbMusicOffTopic, sbFiller) },
        )
        assertEquals(chosen, store.settings().first().settings.segmentActions)
    }

    /** What a later build might store: each value this one doesn't offer reads as its default. */
    @Test
    fun storedValuesThisBuildDoesntKnowReadAsTheirDefaults() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        val known = MOCKUP_DEFAULTS.copy(resume = false).toEntity(owner, T0)
        database.profileSettingsDao().upsert(
            known.copy(speedPercent = 110, captionsLanguage = "not a tag", sbSponsor = "mute", sbFiller = "skip", chatPlacement = "centre"),
        )

        val settings = store.settings().first().settings

        assertEquals(MOCKUP_DEFAULTS.copy(resume = false).withAction(SegmentCategory.Filler, SegmentAction.Skip), settings)
    }

    /** KP-D18: the profile's own filters; a level this build doesn't know fails closed, a length
     *  pair that can't be limits reads as none. */
    @Test
    fun storedFiltersReadLenientlyAndFailClosed() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        val known = MOCKUP_DEFAULTS.toEntity(owner, T0)
        database.profileSettingsDao().upsert(known.copy(restrictedMode = "moderate", hideLive = true, minLengthMinutes = 20, maxLengthMinutes = 5))

        val settings = store.settings().first().settings

        assertEquals(MOCKUP_DEFAULTS.copy(restrictedMode = RestrictedMode.Strict, hideLive = true), settings)
    }

    @Test
    fun aRowFromBeforeTheFiltersReadsThemAsOff() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        database.profileSettingsDao().upsert(MOCKUP_DEFAULTS.toEntity(owner, T0).copy(restrictedMode = null))

        assertEquals(MOCKUP_DEFAULTS, store.settings().first().settings)
    }

    /** KP-D11: the state says when the session profile is a kids profile. */
    @Test
    fun aKidsProfilesStateSaysItIsOne() = scope.runTest {
        val owner = profiles.ensureOwner().id
        database.profileDao().setLock(owner, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
        session.select(owner)
        assertFalse(store.settings().first().kids)

        session.select(kid)

        assertEquals(ProfileSettingsState(kid, MOCKUP_DEFAULTS, kids = true), store.settings().first())
    }

    private fun ProfileSettings.withAction(category: SegmentCategory, action: SegmentAction): ProfileSettings =
        copy(segmentActions = segmentActions + (category to action))
}

/** `b3-settings` as approved, written out field by field rather than read from the defaults. */
private val MOCKUP_DEFAULTS = ProfileSettings(
    speedPercent = 100,
    resume = true,
    captionsLanguage = null,
    chatPlacement = ChatPlacement.Beside,
    sponsorBlock = true,
    segmentActions = mapOf(
        SegmentCategory.Sponsor to SegmentAction.Button,
        SegmentCategory.SelfPromo to SegmentAction.Skip,
        SegmentCategory.Interaction to SegmentAction.Skip,
        SegmentCategory.Intro to SegmentAction.Button,
        SegmentCategory.Outro to SegmentAction.Button,
        SegmentCategory.Preview to SegmentAction.Button,
        SegmentCategory.Hook to SegmentAction.Bar,
        SegmentCategory.MusicOffTopic to SegmentAction.Button,
        SegmentCategory.Filler to SegmentAction.Off,
    ),
    skipNote = true,
    dearrowTitles = true,
    dearrowThumbnails = true,
    dislikes = true,
    showShorts = false,
    forYou = true,
    restrictedMode = RestrictedMode.Off,
    hideLive = false,
    hideMembersOnly = false,
    lengthLimits = LengthLimits(),
)

private const val T0 = 1_700_000_000_000L

/** Two of Islamic content only's channels (`IslamicOnly`): on by default, and off. */
private const val ALAFASY = "UCmMcOjsVehVlEOteyrhjI2Q"
private const val TWO_HOLY_MOSQUES = "UCdAnnrdcG0t8x1qDjVgaIfQ"
