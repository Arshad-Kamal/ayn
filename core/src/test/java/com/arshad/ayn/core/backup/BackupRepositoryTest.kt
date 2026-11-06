package com.arshad.ayn.core.backup

import android.net.Uri
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.FilterRepository
import com.arshad.ayn.core.store.ForYouRepository
import com.arshad.ayn.core.store.Impression
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.KidsTimeRepository
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.MutableClock
import com.arshad.ayn.core.store.OWNER_MARK
import com.arshad.ayn.core.store.PlaylistRepository
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileEntity
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsRepository
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionRepository
import com.arshad.ayn.core.store.WATCH_LATER_SYNC_ID
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.WatchRepository
import com.arshad.ayn.core.store.ensureOwner
import com.arshad.ayn.core.store.kind
import com.arshad.ayn.core.sync.SyncDatabase
import com.arshad.ayn.core.sync.mergeSnapshots
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [BackupRepository] over a real in-memory Room 3 database and an in-memory [BackupFiles]:
 * export → restore → export, first-launch and per-profile restores (Replace, Add as new, skips,
 * device settings; KP-D24), a failed restore that changes nothing, refusals, and imports into the
 * current profile (M5-D4/D5/D6/D14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryTest {
    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
    private val clock = MutableClock(T0)
    private val session = ProfileSession()
    private val profiles = ProfileRepository(database, clock)
    private val subscriptions = SubscriptionRepository(database, session, clock)
    private val watch = WatchRepository(database, session, clock)
    private val playlists = PlaylistRepository(database, session, clock)
    private val settings = ProfileSettingsRepository(database, session, clock)
    private val forYou = ForYouRepository(database, session, clock)
    private val filters = FilterRepository(database, clock)
    private val files = FakeBackupFiles()
    private val device = FakeDeviceSettingsStore()
    private val backups = backupsIn(ZoneOffset.UTC)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun exportThenRestoreThenExportGivesTheSameBackup() = scope.runTest {
        fillTwoProfiles()
        val first = exportedBundle()
        clock.nowMillis = T0 + DAY
        subscriptions.subscribe(channel(CHANNEL_C, "Added after the backup"))

        assertTrue(restoreAll(opened(files.lastUri)) is RestoreResult.Restored)

        assertEquals(first.copy(exportedAtMillis = 0), exportedBundle().copy(exportedAtMillis = 0))
        assertEquals(listOf(V2.value), first.profiles.first().history.filter { it.short }.map { it.videoId })
        assertEquals(BackupFilterPacks(halalOnly = true, stageMagic = true, festivals = true), first.profiles[0].settings?.filterPacks)
        assertEquals(
            BackupFilterPacks(
                harmfulForKids = true,
                challengesAndVlogs = true,
                islamicOnly = true,
                islamicChannels = mapOf(ALAFASY to false, TWO_HOLY_MOSQUES to true),
            ),
            first.profiles[1].restrictions?.filterPacks,
        )
    }

    /** FPK-D21: a kids profile with no restrictions row (made before v11) exports none and restores
     *  as it was, with no pack turned on. */
    @Test
    fun aKidsProfileWithoutRestrictionsRestoresWithNone() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        val kid = database.profileDao().insert(ProfileEntity(name = "Zainab", tint = "rose", ownerMark = null, createdAtMillis = 1, updatedAtMillis = 1, kids = true))
        val first = exportedBundle()
        assertEquals(null, first.profiles.single { it.kids }.restrictions)

        backups.restore(OwnerPass(), opened(files.lastUri), listOf(RestoreChoice.Skip, RestoreChoice.Replace(kid)))

        assertEquals(ProfileRestrictions(), filters.rules(kid).first().restrictions)
        assertEquals(first.copy(exportedAtMillis = 0), exportedBundle().copy(exportedAtMillis = 0))
    }

    /** FPK-D21: a file whose kid carries no restrictions replaces the kid's, Harmful for kids from
     *  its creation included. */
    @Test
    fun replacingAKidFromAFileWithoutRestrictionsLeavesItNone() = scope.runTest {
        val owner = profiles.ensureOwner().id
        database.profileDao().setLock(owner, kind = "pin", hash = "h", salt = "s", iterations = 1)
        session.select(owner)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Rose, kids = true) as ProfileEdit.Done).profileId
        val text = """{"bundleVersion":5,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Zainab","tint":"rose",""" +
            """"owner":false,"kids":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[]}]}"""

        backups.restore(OwnerPass(), validBackup(text), listOf(RestoreChoice.Replace(kid)))

        assertEquals(ProfileRestrictions(), filters.rules(kid).first().restrictions)
    }

    /** FPK-D21: a switch for a channel this build doesn't list is skipped and named; only the
     *  profile's own count in its settings total, the owner's are skips as other restrictions'. */
    @Test
    fun islamicSwitchesForChannelsThisBuildDoesntListAreSkipped() = scope.runTest {
        val packs = """{"islamicOnly":true,"islamicChannels":{"$ALAFASY":false,"UCgoneeeeeeeeeeeeeeeeeee":true}}"""
        val text = """{"bundleVersion":5,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Owner","tint":"amber",""" +
            """"owner":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[],""" +
            """"settings":{"speedPercent":100,"resume":true,"sponsorBlock":true,"segmentActions":{},"skipNote":true,""" +
            """"dearrowTitles":true,"dearrowThumbnails":true,"dislikes":true,"showShorts":false,"updatedAtMillis":1,""" +
            """"filterPacks":$packs},"restrictions":{"restrictedMode":"off","hideShorts":false,"hideLive":false,""" +
            """"hideMembersOnly":false,"updatedAtMillis":1,"filterPacks":$packs}}]}"""

        val restored = (restoreAll(validBackup(text)) as RestoreResult.Restored).profiles.single()

        val listed = FilterPacks(islamicOnly = true, islamicChannels = mapOf(ALAFASY to false))
        val owner = profiles.profiles().first().single().id
        assertEquals(listed, settings.settings().first().settings.filterPacks)
        assertEquals(listed, filters.rules(owner).first().restrictions.filterPacks)
        assertEquals(
            listOf(
                SettingSkip("filterPacks.islamicChannels.UCgoneeeeeeeeeeeeeeeeeee", "true", null),
                SettingSkip("restrictions.filterPacks.islamicChannels.UCgoneeeeeeeeeeeeeeeeeee", "true", null),
            ),
            restored.skips,
        )
        assertEquals(SETTINGS_PER_PROFILE to SETTINGS_PER_PROFILE + 1, restored.settingsApplied to restored.settingsTotal)
    }

    @Test
    fun aBackupHoldsEveryRecordKindOfEveryProfile() = scope.runTest {
        fillTwoProfiles()

        val bundle = exportedBundle()

        assertEquals("Ayn" to 6, bundle.app to bundle.bundleVersion)
        assertEquals(
            listOf(
                "Owner owner subs=2 history=3 playlists=[Mix:2, Empty:0] watchLater=1 hidden=1/1",
                "Mira subs=1 history=0 playlists=[] watchLater=0 hidden=1/0",
            ),
            bundle.profiles.map { p ->
                "${p.name}${if (p.owner) " owner" else ""} subs=${p.subscriptions.size} history=${p.history.size} " +
                    "playlists=${p.playlists.map { "${it.name}:${it.videos.size}" }} watchLater=${p.watchLater?.videos?.size ?: 0} " +
                    "hidden=${p.hiddenVideos.size}/${p.hiddenChannels.size}"
            },
        )
    }

    /** A profile still on the defaults exports no settings, so it takes a later build's defaults. */
    @Test
    fun onlyAProfileThatChangedASettingCarriesSettings() = scope.runTest {
        fillTwoProfiles()

        val bundle = exportedBundle()

        assertEquals(listOf(true, false), bundle.profiles.map { it.settings != null })
        assertEquals(SETTINGS_CHANGED_AT, bundle.profiles[0].settings?.updatedAtMillis)
    }

    @Test
    fun theGoldenVersionTwoFileRestoresEverySettingAndItsTime() = scope.runTest {
        restoreAll(opened(files.put(resource("backup-v2-golden.json"))))

        assertEquals(
            ProfileSettings(
                speedPercent = 125,
                resume = false,
                captionsLanguage = "en-GB",
                segmentActions = mapOf(
                    SegmentCategory.Sponsor to SegmentAction.Skip,
                    SegmentCategory.SelfPromo to SegmentAction.Skip,
                    SegmentCategory.Interaction to SegmentAction.Off,
                    SegmentCategory.Intro to SegmentAction.Bar,
                    SegmentCategory.Outro to SegmentAction.Button,
                    SegmentCategory.Preview to SegmentAction.Button,
                    SegmentCategory.Hook to SegmentAction.Bar,
                    SegmentCategory.MusicOffTopic to SegmentAction.Off,
                    SegmentCategory.Filler to SegmentAction.Button,
                ),
                skipNote = false,
                dearrowThumbnails = false,
                showShorts = true,
            ),
            settings.settings().first().settings,
        )
        val rows = database.profileSettingsDao().all()
        assertEquals(listOf(session.currentId() to 1_791_200_000_000), rows.map { it.profileId to it.updatedAtMillis })
    }

    /** The golden file's mixed values pin every exported field to its own; a v2 file has no
     *  Restricted Mode, which restores as Off, and no chat placement, which stays beside the video. */
    @Test
    fun theGoldenVersionTwoFileExportsTheSettingsItRestored() = scope.runTest {
        val golden = files.put(resource("backup-v2-golden.json"))
        restoreAll(opened(golden))

        val exported = exportedBundle()

        assertEquals(
            opened(golden).bundle.profiles.map { it.settings?.copy(restrictedMode = "off", filterPacks = BackupFilterPacks(), chatPlacement = "beside") },
            exported.profiles.map { it.settings },
        )
    }

    @Test
    fun theGoldenVersionThreeFileRestoresForYouOffAndEachProfilesHiddenVideosAndChannels() = scope.runTest {
        restoreAll(opened(files.put(resource("backup-v3-golden.json"))))

        assertEquals(false, settings.settings().first().settings.forYou)
        assertEquals(setOf(VideoId("dQw4w9WgXcQ"), VideoId("njX2bu-_Vw4")), forYou.hiddenVideos().first())
        assertEquals(setOf("https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"), forYou.hiddenChannels().first())
        assertEquals(
            listOf("dQw4w9WgXcQ" to 1_791_350_000_000, "njX2bu-_Vw4" to 1_791_360_000_000, "JsBZOcqZerk" to 1_791_380_000_000),
            database.forYouDao().allHiddenVideos().map { it.videoId to it.hiddenAtMillis },
        )
    }

    @Test
    fun theGoldenVersionThreeFileExportsTheFeedbackAndSwitchItRestored() = scope.runTest {
        val golden = files.put(resource("backup-v3-golden.json"))
        restoreAll(opened(golden))

        val exported = exportedBundle()

        val expected = opened(golden).bundle.profiles
        assertEquals(
            expected.map { Triple(it.settings?.copy(restrictedMode = "off", filterPacks = BackupFilterPacks(), chatPlacement = "beside"), it.hiddenVideos, it.hiddenChannels) },
            exported.profiles.map { Triple(it.settings, it.hiddenVideos, it.hiddenChannels) },
        )
    }

    @Test
    fun theGoldenVersionFourFileRestoresKidsProfilesFiltersAndRules() = scope.runTest {
        restoreAll(opened(files.put(resource("backup-v4-golden.json"))))

        val restored = profiles.profiles().first()
        assertEquals(listOf("Arshad" to false, "Zainab" to true), restored.map { it.name to it.isKids })
        val owner = restored[0].id
        val zainab = restored[1].id
        assertEquals(
            ProfileSettings(restrictedMode = RestrictedMode.Strict, hideLive = true, lengthLimits = LengthLimits(maxMinutes = 60)),
            settings.settings().first().settings,
        )
        assertEquals(listOf(StoredRule(Keyword("prank"), byOwner = false, 1_791_410_000_000)), filters.rules(owner).first().rules)
        val kid = filters.rules(zainab).first()
        assertEquals(
            ProfileRestrictions(
                restrictedMode = RestrictedMode.Strict,
                hideShorts = true,
                hideLive = true,
                hideMembersOnly = true,
                dailyLimitMinutes = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.SATURDAY to 90),
                bedtime = Bedtime(startMinute = 1_170, endMinute = 420),
            ),
            kid.restrictions,
        )
        assertEquals(listOf("approved_video:JsBZOcqZerk", "video:dQw4w9WgXcQ"), kid.rules.map { "${it.rule.kind}:${it.rule.key}" })
    }

    @Test
    fun theGoldenVersionFourFileExportsTheKidsProfileRestrictionsAndRulesItRestored() = scope.runTest {
        val golden = files.put(resource("backup-v4-golden.json"))
        restoreAll(opened(golden))

        val exported = exportedBundle()

        val expected = opened(golden).bundle.profiles
        assertEquals(
            expected.map { Triple(it.kids, it.restrictions?.copy(filterPacks = BackupFilterPacks()), it.rules) },
            exported.profiles.map { Triple(it.kids, it.restrictions, it.rules) },
        )
    }

    /** FPK-D20: both setters' packs, options and Islamic switches restore, and export as they came. */
    @Test
    fun theGoldenVersionFiveFileRestoresAndExportsBothSettersPacks() = scope.runTest {
        val golden = files.put(resource("backup-v5-golden.json"))
        restoreAll(opened(golden))

        val zainab = profiles.profiles().first()[1].id
        assertEquals(FilterPacks(halalOnly = true, lootBoxes = true), settings.settings().first().settings.filterPacks)
        assertEquals(
            FilterPacks(
                harmfulForKids = true,
                overstimulating = true,
                islamicOnly = true,
                islamicChannels = mapOf(ALAFASY to false, TWO_HOLY_MOSQUES to true),
            ),
            filters.rules(zainab).first().restrictions.filterPacks,
        )
        val expected = opened(golden).bundle.profiles
        assertEquals(
            expected.map { it.settings?.filterPacks to it.restrictions },
            exportedBundle().profiles.map { it.settings?.filterPacks to it.restrictions },
        )
    }

    /** KP-D17, D18: a rule the store wouldn't take is left out; approvals and patterns only count
     *  from the owner, so a file can't make a kids profile approve for itself. */
    @Test
    fun restoredRulesKeepOnlyWhatTheStoreWouldHaveTaken() = scope.runTest {
        fun rule(kind: String, value: String, byOwner: Boolean): String =
            """{"kind":"$kind","value":"$value","byOwner":$byOwner,"title":"T","addedAtMillis":1}"""
        val rules = listOf(
            rule("approved_video", "JsBZOcqZerk", byOwner = false),
            rule("pattern", "free v-?bucks", byOwner = false),
            rule("topic", "news", byOwner = true),
            rule("video", "short", byOwner = true),
            rule("keyword", " prank ", byOwner = true),
            rule("keyword", "prank", byOwner = true),
            rule("channel", "$CHANNEL_B/videos", byOwner = false),
        ).joinToString(",")
        val text = """{"bundleVersion":4,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Owner","tint":"amber",""" +
            """"owner":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[],""" +
            """"rules":[$rules]}]}"""

        restoreAll(validBackup(text))

        assertEquals(
            setOf(StoredRule(Keyword("prank"), byOwner = true, 1), StoredRule(BlockedChannel(CHANNEL_B, null), byOwner = false, 1)),
            filters.rules(profiles.profiles().first().single().id).first().rules.toSet(),
        )
    }

    /** LIVE-D21: where a profile's chat shows is exported under its key and restored. */
    @Test
    fun aProfilesChatPlacementIsExportedAndRestored() = scope.runTest {
        fillTwoProfiles()
        val owner = session.currentId()
        settings.update(owner) { it.copy(chatPlacement = ChatPlacement.TopRight) }

        val bundle = exportedBundle()
        settings.update(owner) { it.copy(chatPlacement = ChatPlacement.Beside) }
        restoreAll(opened(files.lastUri))

        assertEquals("top-right", bundle.profiles[0].settings?.chatPlacement)
        assertEquals(ChatPlacement.TopRight, settings.settings().first().settings.chatPlacement)
    }

    /** Beside, the default, is written too, so an older build's copy without the key is told apart. */
    @Test
    fun aChangedProfileExportsBesideTheVideoUnderItsKey() = scope.runTest {
        fillTwoProfiles()

        backups.export(OwnerPass(), setOf(session.currentId())) as ExportResult.Saved

        assertTrue(files.lastBytes.decodeToString().contains(""""chatPlacement":"beside""""))
    }

    /** A file or sync copy without the key (an older build's) keeps the profile's own placement. */
    @Test
    fun aSettingsBlockWithoutChatPlacementKeepsTheProfilesOwn() = scope.runTest {
        val exported = exportedWithChatPlacement(ChatPlacement.Side)
        val text = exported.replace(""","chatPlacement":"side"""", "")
        assertNotEquals(exported, text)

        val restored = restoreAll(validBackup(text)) as RestoreResult.Restored

        assertEquals(ChatPlacement.TopLeft, settings.settings().first().settings.chatPlacement)
        assertEquals(emptyList<SettingSkip>(), restored.profiles.flatMap { it.skips })
    }

    /** A placement this build doesn't offer is skipped, keeping the profile's own. */
    @Test
    fun anUnknownChatPlacementIsSkippedKeepingTheProfilesOwn() = scope.runTest {
        val text = exportedWithChatPlacement(ChatPlacement.Side).replace(""""chatPlacement":"side"""", """"chatPlacement":"centre"""")

        val restored = restoreAll(validBackup(text)) as RestoreResult.Restored

        assertEquals(ChatPlacement.TopLeft, settings.settings().first().settings.chatPlacement)
        assertEquals(listOf(SettingSkip("chatPlacement", "centre", "top-left")), restored.profiles.flatMap { it.skips })
    }

    /** The owner's export with [placement], the owner then moved to Top-left. */
    private suspend fun exportedWithChatPlacement(placement: ChatPlacement): String {
        fillTwoProfiles()
        val owner = session.currentId()
        settings.update(owner) { it.copy(chatPlacement = placement) }
        backups.export(OwnerPass(), setOf(owner)) as ExportResult.Saved
        settings.update(owner) { it.copy(chatPlacement = ChatPlacement.TopLeft) }
        return files.lastBytes.decodeToString()
    }

    /** FY-D6: impressions stay out of backups, so a restore starts them fresh. */
    @Test
    fun aRestoreLeavesNoImpressions() = scope.runTest {
        fillTwoProfiles()
        forYou.recordShown(listOf(card(V1)))
        exportedBundle()

        restoreAll(opened(files.lastUri))

        assertEquals(emptyList<Impression>(), forYou.impressions())
    }

    @Test
    fun aHiddenChannelRestoresUnderItsCanonicalUrl() = scope.runTest {
        val text = """{"bundleVersion":3,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Owner","tint":"amber",""" +
            """"owner":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[],""" +
            """"hiddenChannels":[{"channelUrl":"$CHANNEL_B/videos","hiddenAtMillis":5}]}]}"""

        restoreAll(validBackup(text))

        assertEquals(setOf(CHANNEL_B), forYou.hiddenChannels().first())
    }

    @Test
    fun aVersionTwoBackupRestoresForYouOnWithNothingHidden() = scope.runTest {
        restoreAll(opened(files.put(resource("backup-v2-golden.json"))))

        assertEquals(true, settings.settings().first().settings.forYou)
        assertEquals(emptyList<Any>(), database.forYouDao().allHiddenVideos())
    }

    @Test
    fun aVersionOneBackupRestoresEveryProfileOnTheDefaults() = scope.runTest {
        restoreAll(validBackup(HAND_WRITTEN_BACKUP))

        assertEquals(ProfileSettings(), settings.settings().first().settings)
        assertEquals(0, database.profileSettingsDao().all().size)
    }

    /** What a later build might write: each value this one doesn't offer restores as its default. */
    @Test
    fun settingValuesThisBuildDoesntKnowRestoreAsTheirDefaults() = scope.runTest {
        val odd = """{"speedPercent":110,"resume":false,"captionsLanguage":"not a tag","sponsorBlock":true,""" +
            """"segmentActions":{"sponsor":"mute","filler":"skip","chapter":"skip"},"skipNote":true,"dearrowTitles":true,""" +
            """"dearrowThumbnails":true,"dislikes":true,"showShorts":false,"updatedAtMillis":9,"chatPlacement":"centre"}"""
        val text = HAND_WRITTEN_BACKUP.replace(""""bundleVersion":1""", """"bundleVersion":2""")
            .replace(""""tint":"chartreuse","owner":true,""", """"tint":"chartreuse","owner":true,"settings":$odd,""")

        val restored = restoreAll(validBackup(text)) as RestoreResult.Restored

        val defaults = ProfileSettings()
        assertEquals(
            defaults.copy(resume = false, segmentActions = defaults.segmentActions + (SegmentCategory.Filler to SegmentAction.Skip)),
            settings.settings().first().settings,
        )
        // KP-D17: each value skipped is counted and named with the value kept.
        val mira = restored.profiles.single { it.name == "Mira" }
        assertEquals(
            listOf(
                SettingSkip("speedPercent", "110", "100"),
                SettingSkip("captionsLanguage", "not a tag", null),
                SettingSkip("segmentActions.sponsor", "mute", "button"),
                SettingSkip("segmentActions.chapter", "skip", null),
                SettingSkip("chatPlacement", "centre", "beside"),
            ),
            mira.skips,
        )
        assertEquals(22 to 27, mira.settingsApplied to mira.settingsTotal)
        assertTrue(restored.partial)
    }

    /** M6-D6: a profile added from a backup takes this device's default for a value it skips. */
    @Test
    fun anAddedProfilesSkippedValueIsThisDevicesDefault() = scope.runTest {
        val mira = restoreMiraOnAPhone().single { it.name == "Mira" }

        assertFalse(mira.replaced)
        assertPhoneDefaultsUnderMirasValues(mira.profileId)
    }

    /** M6-D6: so does a replaced profile that had no settings stored. */
    @Test
    fun aReplacedProfileWithoutSettingsTakesThisDevicesDefaultForASkippedValue() = scope.runTest {
        val owner = (profiles.createOwner("Mira", ProfileTint.Amber) as ProfileEdit.Done).profileId

        val mira = restoreMiraOnAPhone().single { it.name == "Mira" }

        assertEquals(owner to true, mira.profileId to mira.replaced)
        assertPhoneDefaultsUnderMirasValues(owner)
    }

    /** Mira's settings with a speed this build doesn't offer (110 %), restored by a phone whose
     *  defaults show Shorts at 125 %. */
    private suspend fun restoreMiraOnAPhone(): List<RestoredProfile> {
        val offStepSpeed = """{"speedPercent":110,"resume":false,"sponsorBlock":true,"segmentActions":{},"skipNote":true,""" +
            """"dearrowTitles":true,"dearrowThumbnails":true,"dislikes":true,"showShorts":false,"updatedAtMillis":9}"""
        val text = HAND_WRITTEN_BACKUP.replace(""""bundleVersion":1""", """"bundleVersion":2""")
            .replace(""""tint":"chartreuse","owner":true,""", """"tint":"chartreuse","owner":true,"settings":$offStepSpeed,""")
        val phone = BackupRepository(database, session, files, device, clock, ZoneOffset.UTC, dispatcher, PHONE_DEFAULTS)
        val backup = validBackup(text)
        val restored = phone.restore(OwnerPass(), backup, defaultChoices(backup)) as RestoreResult.Restored
        return restored.profiles
    }

    private suspend fun assertPhoneDefaultsUnderMirasValues(miraId: Long) {
        session.select(miraId)
        val restored = settings.settings().first().settings
        assertEquals("skipped: this device's default", PHONE_DEFAULTS.speedPercent, restored.speedPercent)
        assertFalse("written: the backup's resume", restored.resume)
        assertFalse("written: the backup's Shorts", restored.showShorts)
    }

    @Test
    fun aFirstLaunchRestoreAddsEveryProfileAndSelectsTheBackupsOwner() = scope.runTest {
        val restored = restoreAll(validBackup(HAND_WRITTEN_BACKUP)) as RestoreResult.Restored

        val now = profiles.profiles().first()
        assertEquals(listOf("Mira" to true, "Yuri" to false), now.map { it.name to it.isOwner })
        assertEquals(restored.ownerId, now.first().id)
        assertEquals(restored.ownerId, session.currentId())
        assertEquals(listOf("Yuri" to false, "Mira" to false), restored.profiles.map { it.name to it.replaced })
        assertEquals(
            listOf(Subscription(CHANNEL_B, "From the backup", avatarUrl = null, subscribedAtMillis = 111, updatedAtMillis = 222)),
            subscriptions.subscriptions().first(),
        )
        assertEquals(1, database.subscriptionDao().all().size)
    }

    /** The Shield's own export from the M5.10 pass: Bijan Bowen stored under two URL forms (M5P-1). */
    @Test
    fun aBackupHoldingOneChannelUnderTwoUrlFormsRestoresItOnce() = scope.runTest {
        val restored = restoreAll(opened(files.put(resource("shield-2026-10-06-two-forms.json")))) as RestoreResult.Restored

        assertEquals(restored.ownerId, session.currentId())
        assertEquals(
            listOf(
                Subscription(
                    channelUrl = "https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg",
                    name = "Bijan Bowen",
                    // The more recently updated row's picture, the earlier row's subscribe date.
                    avatarUrl = "https://yt3.ggpht.com/At-1Kn54AljCodKZ7G9jxzd-1cCR3BknxIqpwGiXckFOXATpOjSyL-ivxtZQPE7RRAtWC8abFA=s176-c-k-c0x00ffffff-no-rj",
                    subscribedAtMillis = 1_791_104_005_906,
                    updatedAtMillis = 1_791_104_025_908,
                ),
            ),
            subscriptions.subscriptions().first(),
        )
    }

    @Test
    fun restoredRecordsKeepEveryValueTheBackupHolds() = scope.runTest {
        restoreAll(validBackup(HAND_WRITTEN_BACKUP))

        assertEquals(
            listOf(WatchEntry(V1, "Watched", null, null, null, positionMs = 590_000, durationMs = 600_000, watched = true, 333, 444)),
            watch.history().first(),
        )
        val roadTrip = playlists.playlists().first().single()
        assertEquals("Road trip" to 20L, roadTrip.name to roadTrip.updatedAtMillis)
        assertEquals(listOf(V2 to 30L, V1 to 40L), playlists.videos(roadTrip.id).first().map { it.video.id to it.addedAtMillis })
        assertEquals(listOf(V3), playlists.watchLater().first().map { it.video.id })
    }

    @Test
    fun aRestoreThatFailsPartWayChangesNothing() = scope.runTest {
        fillTwoProfiles()
        val before = exportedBundle()
        val selected = session.currentId()
        val bundle = (decodeBackup(HAND_WRITTEN_BACKUP.encodeToByteArray()) as BackupRead.Valid).backup.bundle
        val mira = bundle.profiles[1]
        // A video listed twice in the second profile's history: the checks refuse it, so the backup
        // is built directly to make the database fail after the first profile went in.
        val broken = bundle.copy(profiles = listOf(bundle.profiles[0], mira.copy(history = mira.history + mira.history)))

        assertEquals(RestoreResult.Failed, restoreAll(ValidBackup(broken)))

        assertEquals(before.copy(exportedAtMillis = 0), exportedBundle().copy(exportedAtMillis = 0))
        assertEquals(selected, session.currentId())
    }

    @Test
    fun aRestoreWhoseCallerIsCancelledStillFinishesAndSelectsTheOwner() = scope.runTest {
        val backup = validBackup(HAND_WRITTEN_BACKUP)
        val choices = defaultChoices(backup)

        launch {
            coroutineContext.job.cancel()
            backups.restore(OwnerPass(), backup, choices)
        }
        advanceUntilIdle()

        val now = profiles.profiles().first()
        assertEquals(listOf("Mira", "Yuri"), now.map { it.name })
        assertEquals(now.first().id, session.currentId())
    }

    @Test
    fun anExportThatCantReadTheDatabaseFailsAndSavesNothing() = scope.runTest {
        session.select(profiles.ensureOwner().id)
        database.useWriterConnection { connection -> connection.usePrepared("DROP TABLE watch_entries") { it.step() } }

        assertEquals(ExportResult.Failed, backups.export(OwnerPass()))
        assertEquals(0, files.stored.size)
    }

    @Test
    fun namesTheDatabaseKeepsApartOnlyByNonAsciiCaseRestore() = scope.runTest {
        val text = HAND_WRITTEN_BACKUP.replace("\"Yuri\"", "\"émile\"").replace("\"Mira\"", "\"Émile\"")

        assertTrue(restoreAll(validBackup(text)) is RestoreResult.Restored)

        assertEquals(listOf("Émile", "émile"), profiles.profiles().first().map { it.name })
    }

    @Test
    fun aTintThisBuildDoesntKnowRestoresAsAmber() = scope.runTest {
        restoreAll(validBackup(HAND_WRITTEN_BACKUP))

        assertEquals(listOf(ProfileTint.Amber, ProfileTint.Teal), profiles.profiles().first().map { it.tint })
    }

    /** KP-D24: Replace keeps the profile's lock and tries on this device. */
    @Test
    fun backupsCarryNoLockSecretsAndAReplacedProfileKeepsItsLock() = scope.runTest {
        database.profileDao().insert(
            ProfileEntity(
                name = "Arshad",
                tint = ProfileTint.Amber.key,
                ownerMark = OWNER_MARK,
                lockKind = "pin",
                lockHash = "SECRET-HASH",
                lockSalt = "SECRET-SALT",
                lockIterations = 10_000,
                failedTries = 3,
                waitUntilMillis = T0 + DAY,
                createdAtMillis = T0,
                updatedAtMillis = T0,
            ),
        )

        backups.export(OwnerPass())
        val text = files.lastBytes.decodeToString()
        restoreAll(opened(files.lastUri))

        assertFalse(text, text.contains("SECRET") || text.contains("\"lock"))
        val row = database.profileDao().all().single()
        assertEquals(
            listOf("pin", "SECRET-HASH", "SECRET-SALT", 10_000, 3, T0 + DAY),
            listOf(row.lockKind, row.lockHash, row.lockSalt, row.lockIterations, row.failedTries, row.waitUntilMillis),
        )
    }

    @Test
    fun aFileThatCantBeReadIsRefusedAsUnreadable() = scope.runTest {
        assertEquals(BackupRead.Refused(BackupRefusal.Unreadable), backups.open(Uri.parse("content://test/missing")))
    }

    @Test
    fun deletingASavedBackupRemovesItsFileAndAGoneOneSaysSo() = scope.runTest {
        val saved = files.put(resource("backup-v4-golden.json"))

        assertTrue(backups.deleteBackup(OwnerPass(), saved))
        assertEquals(BackupRead.Refused(BackupRefusal.Unreadable), backups.open(saved))
        assertFalse(backups.deleteBackup(OwnerPass(), saved))
    }

    @Test
    fun aFileOverTheCapIsRefusedAsTooLarge() = scope.runTest {
        val huge = files.put(ByteArray(MAX_BACKUP_BYTES + 1))

        assertEquals(BackupRead.Refused(BackupRefusal.TooLarge), backups.open(huge))
    }

    @Test
    fun theBackupIsNamedForTheDayOnTheTv() = scope.runTest {
        session.select(profiles.ensureOwner().id)
        clock.nowMillis = OCT_4_2026_2330_UTC

        assertEquals("Ayn-backup-2026-10-04.json", (backups.export(OwnerPass()) as ExportResult.Saved).fileName)
        assertEquals("Ayn-backup-2026-10-05.json", (backupsIn(ZoneOffset.ofHours(1)).export(OwnerPass()) as ExportResult.Saved).fileName)
    }

    @Test
    fun aBackupThatDoesntReadBackIsNotSaved() = scope.runTest {
        session.select(profiles.ensureOwner().id)
        files.cutWritesShort = true

        assertEquals(ExportResult.Failed, backups.export(OwnerPass()))
        assertEquals(0, files.stored.size)
    }

    @Test
    fun aTakeoutImportAddsChannelsToTheProfileThatAskedEvenWithAnotherCurrent() = scope.runTest {
        val owner = profiles.ensureOwner().id
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        session.select(owner)

        val result = backups.importSubscriptions(files.put(resource("takeout-subscriptions-en.csv")), mira)

        assertEquals(ImportResult.Imported(added = 3, alreadySubscribed = 0), result)
        assertEquals(emptyList<Subscription>(), subscriptions.subscriptions().first())
        session.select(mira)
        assertEquals(
            listOf(RAYCEVICK_URL, JOJI_URL, COMMA_URL).sorted(),
            subscriptions.subscriptions().first().map { it.channelUrl },
        )
    }

    @Test
    fun anImportKeepsAnAlreadySubscribedChannelsRowAndDate() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        subscriptions.subscribe(channel(RAYCEVICK_URL, "Raycevick (mine)", avatarUrl = "https://img/r"))
        clock.nowMillis = T0 + DAY

        val result = backups.importSubscriptions(files.put(resource("takeout-subscriptions-en.csv")), owner)

        assertEquals(ImportResult.Imported(added = 2, alreadySubscribed = 1), result)
        assertEquals(
            Subscription(RAYCEVICK_URL, "Raycevick (mine)", "https://img/r", subscribedAtMillis = T0, updatedAtMillis = T0),
            subscriptions.subscription(RAYCEVICK_URL).first(),
        )
        assertEquals(T0 + DAY, subscriptions.subscription(JOJI_URL).first()?.subscribedAtMillis)
    }

    @Test
    fun aNewPipeExportImportsItsYouTubeChannels() = scope.runTest {
        val owner = profiles.ensureOwner().id

        val result = backups.importSubscriptions(files.put(resource("newpipe-subscriptions.json")), owner)

        assertEquals(ImportResult.Imported(added = 2, alreadySubscribed = 0), result)
    }

    @Test
    fun anImportFileThatCantBeReadOrIsTooLargeAddsNothing() = scope.runTest {
        val owner = profiles.ensureOwner().id
        session.select(owner)

        assertEquals(ImportResult.Unreadable, backups.importSubscriptions(Uri.parse("content://test/missing"), owner))
        assertEquals(ImportResult.TooLarge, backups.importSubscriptions(files.put(ByteArray(MAX_SUBSCRIPTIONS_BYTES + 1)), owner))
        assertEquals(emptyList<Subscription>(), subscriptions.subscriptions().first())
    }

    @Test
    fun anImportIntoAProfileThatIsGoneFailsAndAddsNothing() = scope.runTest {
        val gone = profiles.ensureOwner().id + 1

        assertEquals(ImportResult.Failed, backups.importSubscriptions(files.put(resource("takeout-subscriptions-en.csv")), gone))

        assertEquals(0, database.subscriptionDao().all().size)
    }

    /** KP-D10/D11: a kids profile's channels are the owner's approvals, never an import. */
    @Test
    fun anImportIntoAKidsProfileFailsAndAddsNothing() = scope.runTest {
        val owner = profiles.ensureOwner().id
        database.profileDao().setLock(owner, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId

        assertEquals(ImportResult.Failed, backups.importSubscriptions(files.put(resource("takeout-subscriptions-en.csv")), kid))

        assertEquals(0, database.subscriptionDao().all().size)
    }

    /** KP-D24: one profile restored beside the others: only it changes, the owner stays the owner. */
    @Test
    fun aSingleProfileRestoreReplacesOnlyThatProfile() = scope.runTest {
        fillTwoProfiles()
        val backup = opened(exportedFile())
        val owner = session.currentId()
        subscriptions.subscribe(channel(CHANNEL_C, "Owner's, after the backup"))
        val mira = profiles.profiles().first().single { it.name == "Mira" }.id
        session.select(mira)
        subscriptions.unsubscribe(CHANNEL_A)
        session.select(owner)

        val plan = checkNotNull(backups.restorePlan(backup))
        assertEquals(listOf(owner, mira), plan.profiles.map { it.replaces })
        val restored = backups.restore(OwnerPass(), backup, listOf(RestoreChoice.Skip, RestoreChoice.Replace(mira))) as RestoreResult.Restored

        assertEquals(owner, restored.ownerId)
        assertEquals(owner, session.currentId())
        assertEquals(listOf(mira to true), restored.profiles.map { it.profileId to it.replaced })
        assertEquals(3, subscriptions.subscriptions().first().size)
        session.select(mira)
        assertEquals(listOf(CHANNEL_A), subscriptions.subscriptions().first().map { it.channelUrl })
        assertEquals(listOf(true, false), profiles.profiles().first().map { it.isOwner })
    }

    /** M8-D12: a Replace is no removal: the profile keeps its sync id, no record is written, and
     *  the restored Watch later takes the shared sync id. */
    @Test
    fun aReplaceKeepsTheProfilesSyncIdAndRecordsNoRemovals() = scope.runTest {
        fillTwoProfiles()
        val backup = opened(exportedFile())
        val owner = session.currentId()
        val syncIds = database.profileDao().all().associate { it.id to it.syncId }
        subscriptions.unsubscribe(CHANNEL_A)
        val records = database.removalDao().all()

        assertTrue(backups.restore(OwnerPass(), backup, listOf(RestoreChoice.Replace(owner), RestoreChoice.Skip)) is RestoreResult.Restored)

        assertEquals(syncIds, database.profileDao().all().associate { it.id to it.syncId })
        assertEquals(1, records.size)
        assertEquals(records, database.removalDao().all())
        assertEquals(
            listOf(WATCH_LATER_SYNC_ID),
            database.playlistDao().allPlaylists().filter { it.profileId == owner && it.watchLaterMark != null }.map { it.syncId },
        )
    }

    /** M8-D13: an export carries every profile's and playlist's sync id and no removal records; a
     *  first-launch restore on another device keeps them, so the two devices pair by id. */
    @Test
    fun anExportCarriesTheSyncIdsAndAFirstLaunchRestoreElsewhereKeepsThem() = scope.runTest {
        fillTwoProfiles()
        subscriptions.unsubscribe(CHANNEL_A)
        val exported = exportedBundle()
        val profileIds = database.profileDao().all().map { it.syncId }
        val playlistIds = database.playlistDao().allPlaylists().map { it.syncId }.toSet()

        assertEquals(profileIds, exported.profiles.map { it.syncId })
        assertEquals(playlistIds, exported.profiles.flatMap { it.playlists + listOfNotNull(it.watchLater) }.map { it.syncId }.toSet())
        assertEquals(WATCH_LATER_SYNC_ID, exported.profiles[0].watchLater?.syncId)
        assertEquals(emptyList<BackupRemoval>(), exported.removals)
        val elsewhere = freshDatabase()
        val phone = BackupRepository(elsewhere, ProfileSession(), files, device, clock, ZoneOffset.UTC, dispatcher)
        val backup = (phone.open(files.lastUri) as BackupRead.Valid).backup
        assertTrue(phone.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew)) is RestoreResult.Restored)

        assertEquals(profileIds, elsewhere.profileDao().all().map { it.syncId })
        assertEquals(playlistIds, elsewhere.playlistDao().allPlaylists().map { it.syncId }.toSet())
    }

    /** M8-D3/D4: every profile with its ids and every removal record, no device settings. */
    @Test
    fun theSyncSnapshotIsTheExportWithTheRemovalRecordsAndNoDeviceSettings() = scope.runTest {
        fillTwoProfiles()
        subscriptions.unsubscribe(CHANNEL_A)
        val record = BackupRemoval(database.profileDao().all()[0].syncId, "subscription", CHANNEL_A, clock.nowMillis)

        val snapshot = SyncDatabase(database, clock).snapshot()

        assertEquals(exportedBundle().copy(device = null, removals = listOf(record)), snapshot)
        assertEquals(snapshot, (decodeBackup(encodeBackup(snapshot)) as BackupRead.Valid).backup.bundle)
    }

    /** M8-D14: while sync is linked a restore stamps every record's merge time with now, so it
     *  wins over older removals elsewhere; the times records are shown by stay. */
    @Test
    fun aRestoreWhileSyncIsLinkedStampsTheRecordsItWritesWithNow() = scope.runTest {
        fillTwoProfiles()
        filters.addOwn(session.currentId(), Keyword("prank"))
        val backup = opened(exportedFile())
        val before = exportedBundle().profiles[0]
        clock.nowMillis = T0 + DAY
        val linked = BackupRepository(database, session, files, device, clock, ZoneOffset.UTC, dispatcher, syncLinked = { true })

        assertTrue(linked.restore(OwnerPass(), backup, defaultChoices(backup)) is RestoreResult.Restored)

        val restored = exportedBundle().profiles
        val mergeTimes = restored.flatMap { profile ->
            profile.subscriptions.map { it.updatedAtMillis } + profile.history.map { it.updatedAtMillis } +
                (profile.playlists + listOfNotNull(profile.watchLater)).flatMap { playlist -> listOf(playlist.updatedAtMillis) + playlist.videos.map { it.updatedAtMillis } } +
                listOfNotNull(profile.settings?.updatedAtMillis, profile.restrictions?.updatedAtMillis) +
                profile.hiddenVideos.map { it.hiddenAtMillis } + profile.hiddenChannels.map { it.hiddenAtMillis } + profile.rules.map { it.addedAtMillis }
        }
        assertEquals(setOf(T0 + DAY), mergeTimes.toSet())
        assertTrue("a rule and restrictions are among them", restored[0].rules.isNotEmpty() && restored[1].restrictions != null)
        val owner = restored[0]
        assertEquals(before.history.map { it.lastWatchedAtMillis }, owner.history.map { it.lastWatchedAtMillis })
        assertEquals(before.subscriptions.map { it.subscribedAtMillis }, owner.subscriptions.map { it.subscribedAtMillis })
    }

    /** M8-D13: another device's owner added beside this one's takes a new sync id, so it never
     *  pairs or merges as that device's owner; the file's other profiles keep theirs. */
    @Test
    fun anotherDevicesOwnerAddedAsNewTakesANewSyncIdItsOthersKeepTheirs() = scope.runTest {
        fillTwoProfiles()
        val tvIds = database.profileDao().all().map { it.syncId }
        val phoneDatabase = freshDatabase()
        ProfileRepository(phoneDatabase, clock).ensureOwner()
        val phone = BackupRepository(phoneDatabase, ProfileSession(), files, device, clock, ZoneOffset.UTC, dispatcher)
        val backup = (phone.open(exportedFile()) as BackupRead.Valid).backup

        assertTrue(phone.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew)) is RestoreResult.Restored)

        val phoneIds = phoneDatabase.profileDao().all().map { it.syncId }
        assertEquals(3, phoneIds.size)
        assertFalse(tvIds[0] in phoneIds)
        assertTrue(tvIds[1] in phoneIds)
    }

    /** M8-D13 end to end on the injected clock: a phone started from the TV's export shares its
     *  ids; the TV's later removals (each kind's key as its store records it) beat the phone's
     *  older copies, the phone's later position beats the TV's, and either merge order gives the
     *  same result. */
    @Test
    fun twoDevicesStartedFromOneExportMergeTheirLaterChangesTheSameEitherWay() = scope.runTest {
        fillTwoProfiles()
        val owner = session.currentId()
        filters.addOwn(owner, Keyword("prank"))
        val phoneDatabase = freshDatabase()
        val phoneSession = ProfileSession()
        val phone = BackupRepository(phoneDatabase, phoneSession, files, device, clock, ZoneOffset.UTC, dispatcher)
        val backup = (phone.open(exportedFile()) as BackupRead.Valid).backup
        assertTrue(phone.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew)) is RestoreResult.Restored)
        tick()
        subscriptions.unsubscribe(CHANNEL_A)
        filters.removeOwn(owner, Keyword("prank"))
        playlists.remove(database.playlistDao().allPlaylists().single { it.name == "Mix" }.id, V2)
        forYou.unhideVideo(V2)
        tick()
        WatchRepository(phoneDatabase, phoneSession, clock)
            .recordPosition(PositionSave(phoneSession.currentId(), V1, positionMs = 300_000, durationMs = 600_000, ended = false))

        val tv = SyncDatabase(database, clock).snapshot()
        val phoneSnapshot = SyncDatabase(phoneDatabase, clock).snapshot()
        val merged = mergeSnapshots(tv, phoneSnapshot)

        assertEquals(merged, mergeSnapshots(phoneSnapshot, tv))
        val mergedOwner = merged.profiles[0]
        assertEquals(listOf(CHANNEL_B), mergedOwner.subscriptions.map { it.channelUrl })
        assertEquals(emptyList<BackupRule>(), mergedOwner.rules)
        assertEquals(listOf(V1.value), mergedOwner.playlists.single { it.name == "Mix" }.videos.map { it.videoId })
        assertEquals(emptyList<BackupHiddenVideo>(), mergedOwner.hiddenVideos)
        assertEquals(300_000L, mergedOwner.history.single { it.videoId == V1.value }.positionMs)
        assertEquals(listOf(CHANNEL_A), merged.profiles[1].subscriptions.map { it.channelUrl })
    }

    /** KP-D24: Add as new beside a profile of the same name takes " 2", arrives unlocked, never
     *  as the owner; the device's settings stay. */
    @Test
    fun addAsNewTakesAFreeNameArrivesUnlockedAndIsNoOwner() = scope.runTest {
        fillTwoProfiles()
        val backup = opened(exportedFile())
        val owner = session.currentId()
        database.profileDao().setLock(owner, kind = "pin", hash = "h", salt = "s", iterations = 1)
        device.current.value = DeviceSettings(heroOnHome = false)

        val restored = backups.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew, RestoreChoice.Skip)) as RestoreResult.Restored

        val added = database.profileDao().all().single { it.id == restored.profiles.single().profileId }
        assertEquals(listOf("Owner 2", null, null), listOf(added.name, added.ownerMark, added.lockHash))
        assertNotEquals("the owner here holds the file's sync id (M8-D13)", backup.bundle.profiles[0].syncId, added.syncId)
        assertEquals(backup.bundle.profiles[0].syncId, database.profileDao().all().single { it.id == owner }.syncId)
        assertEquals(owner, restored.ownerId)
        assertFalse(restored.deviceSettings)
        assertEquals(DeviceSettings(heroOnHome = false), device.current.value)
    }

    @Test
    fun choicesThePlanDoesntAllowRestoreNothing() = scope.runTest {
        fillTwoProfiles()
        val backup = opened(exportedFile())
        val owner = session.currentId()
        val before = exportedBundle()

        assertEquals(RestoreResult.Failed, backups.restore(OwnerPass(), backup, listOf(RestoreChoice.Replace(owner + 9), RestoreChoice.Skip)))
        assertEquals(RestoreResult.Failed, backups.restore(OwnerPass(), backup, listOf(RestoreChoice.Skip, RestoreChoice.Skip)))
        assertEquals(RestoreResult.Failed, backups.restore(OwnerPass(), backup, listOf(RestoreChoice.Skip)))

        assertEquals(before.copy(exportedAtMillis = 0), exportedBundle().copy(exportedAtMillis = 0))
    }

    /** The kids flag never changes (KP-D18), so a kids profile in the backup can't replace a
     *  standard one of the same name. */
    @Test
    fun aProfileOfAnotherKindIsNoReplaceTarget() = scope.runTest {
        profiles.ensureOwner()
        profiles.create(OwnerPass(), "Zainab", ProfileTint.Rose)
        val text = """{"bundleVersion":4,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Zainab","tint":"rose",""" +
            """"owner":false,"kids":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[]}]}"""

        val plan = checkNotNull(backups.restorePlan(validBackup(text)))

        assertEquals(listOf(null), plan.profiles.map { it.replaces })
        assertFalse(plan.firstLaunch)
    }

    /** KP-D24: Replace keeps the kid's time ledger; restriction values this build can't apply keep
     *  the replaced profile's (an unknown level applies Strict) and are named. */
    @Test
    fun replacingAKidKeepsItsTimeLedgerAndTheRestrictionsItCantApply() = scope.runTest {
        val owner = profiles.ensureOwner().id
        database.profileDao().setLock(owner, kind = "pin", hash = "h", salt = "s", iterations = 1)
        session.select(owner)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Rose, kids = true) as ProfileEdit.Done).profileId
        val here = Bedtime(startMinute = 19 * 60 + 30, endMinute = 7 * 60)
        filters.updateRestrictions(OwnerPass(), kid) { it.copy(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60), bedtime = here) }
        val ledger = KidsTimeRepository(database)
        ledger.addUsed(kid, KIDS_DAY, 600)
        val text = """{"bundleVersion":4,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Zainab","tint":"rose",""" +
            """"owner":false,"kids":true,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[],""" +
            """"restrictions":{"restrictedMode":"moderate","hideShorts":true,"hideLive":false,"hideMembersOnly":false,""" +
            """"dailyLimitMinutes":{"saturday":5000,"funday":30},"bedtimeStartMinute":600,"bedtimeEndMinute":600,"updatedAtMillis":1}}]}"""

        val restored = backups.restore(OwnerPass(), validBackup(text), listOf(RestoreChoice.Replace(kid))) as RestoreResult.Restored

        val restrictions = filters.rules(kid).first().restrictions
        assertEquals(RestrictedMode.Strict, restrictions.restrictedMode)
        assertEquals(mapOf(DayOfWeek.SATURDAY to 60), restrictions.dailyLimitMinutes)
        assertEquals(here, restrictions.bedtime)
        assertEquals("a v4 file carries no packs: they restore off (FPK-D21)", FilterPacks(), restrictions.filterPacks)
        assertEquals(
            setOf("restrictions.restrictedMode", "restrictions.dailyLimitMinutes.saturday", "restrictions.dailyLimitMinutes.funday", "restrictions.bedtime"),
            restored.profiles.single().skips.map { it.key }.filter { it.startsWith("restrictions.") }.toSet(),
        )
        assertEquals(SettingSkip("restrictions.dailyLimitMinutes.saturday", "5000", "60"), restored.profiles.single().skips.single { it.key.endsWith("saturday") })
        assertEquals(600L, ledger.days(kid, KIDS_DAY, KIDS_DAY).first()[KIDS_DAY]?.usedSeconds)
    }

    @Test
    fun addAsNewPastTheRoomRestoresNothing() = scope.runTest {
        fillTwoProfiles()
        val backup = opened(exportedFile())
        repeat(MAX_PROFILES - 2) { profiles.create(OwnerPass(), "Guest $it", ProfileTint.Rose) }
        val before = database.profileDao().count()
        assertEquals(0, checkNotNull(backups.restorePlan(backup)).roomForNew)

        assertEquals(RestoreResult.Failed, backups.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew, RestoreChoice.Skip)))
        assertEquals(before, database.profileDao().count())
    }

    /** Every profile setting the bundle carries is counted (a new field without a bump would
     *  miscount the results screen): its fields but the SponsorBlock map and the write time,
     *  plus one action per category. */
    @Test
    fun theSettingsCountFollowsTheBundlesFields() {
        assertEquals(BackupSettings.serializer().descriptor.elementsCount - 2 + SegmentCategory.entries.size, SETTINGS_PER_PROFILE)
    }

    /** Q18 (built default): a backup without its owner can't start a device. */
    @Test
    fun aFirstLaunchRestoreOfABackupWithoutItsOwnerIsRefused() = scope.runTest {
        val text = """{"bundleVersion":4,"app":"Ayn","exportedAtMillis":1,"profiles":[{"name":"Mira","tint":"rose",""" +
            """"owner":false,"createdAtMillis":1,"updatedAtMillis":1,"subscriptions":[],"history":[],"playlists":[]}]}"""
        val backup = validBackup(text)

        assertTrue(checkNotNull(backups.restorePlan(backup)).firstLaunch)
        assertEquals(RestoreResult.Failed, restoreAll(backup))
        assertEquals(0, database.profileDao().count())
    }

    /** KP-D17: the chosen profiles and this device's settings go in; at first launch the whole
     *  backup brings the device settings back, a value this build can't apply skipped. */
    @Test
    fun anExportCarriesTheChosenProfilesAndTheDeviceSettingsAFullFirstLaunchRestoreAppliesThem() = scope.runTest {
        fillTwoProfiles()
        val owner = session.currentId()
        device.current.value = DeviceSettings(maxVideoHeight = 1080, clockInPlayer = false, backgroundAudio = false)

        val saved = backups.export(OwnerPass(), setOf(owner)) as ExportResult.Saved
        val bundle = (decodeBackup(files.lastBytes) as BackupRead.Valid).backup.bundle
        assertEquals(listOf("Owner"), saved.profiles.map { it.name })
        assertEquals(ProfileCounts(2, 3, 2, 2, 2, 0), saved.profiles.single().counts)
        assertEquals(BackupDevice(1080, true, 100, false, true, backgroundAudio = false), bundle.device)

        val text = files.lastBytes.decodeToString().replace(""""interfaceScalePercent":100""", """"interfaceScalePercent":999""")
        val fresh = BackupRepository(freshDatabase(), session, files, device.also { it.current.value = DeviceSettings() }, clock, ZoneOffset.UTC, dispatcher)
        val backup = (fresh.open(files.put(text.encodeToByteArray())) as BackupRead.Valid).backup
        val restored = fresh.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew)) as RestoreResult.Restored

        assertTrue(restored.deviceSettings)
        assertEquals(listOf(SettingSkip("device.interfaceScalePercent", "999", "100")), restored.deviceSkips)
        assertEquals(DeviceSettings(maxVideoHeight = 1080, clockInPlayer = false, backgroundAudio = false), device.current.value)
    }

    /** A file written before background audio (M6-D5) restores it as on, the default. */
    @Test
    fun aDeviceSectionWithoutBackgroundAudioRestoresItOn() = scope.runTest {
        fillTwoProfiles()
        device.current.value = DeviceSettings(backgroundAudio = false)
        backups.export(OwnerPass(), setOf(session.currentId())) as ExportResult.Saved
        val exported = files.lastBytes.decodeToString()
        val text = exported.replace(""","backgroundAudio":false""", "")
        assertNotEquals(exported, text)

        val fresh = BackupRepository(freshDatabase(), session, files, device.also { it.current.value = DeviceSettings(backgroundAudio = false) }, clock, ZoneOffset.UTC, dispatcher)
        val backup = (fresh.open(files.put(text.encodeToByteArray())) as BackupRead.Valid).backup
        fresh.restore(OwnerPass(), backup, listOf(RestoreChoice.AddAsNew)) as RestoreResult.Restored

        assertEquals(DeviceSettings(), device.current.value)
    }

    private fun backupsIn(zone: ZoneId): BackupRepository = BackupRepository(database, session, files, device, clock, zone, dispatcher)

    /** Two profiles holding every record kind, at distinct times so every order is fixed. */
    private suspend fun fillTwoProfiles() {
        val owner = profiles.ensureOwner().id
        session.select(owner)
        subscriptions.subscribe(channel(CHANNEL_A, "Channel A", avatarUrl = "https://img/a"))
        tick()
        subscriptions.subscribe(channel(CHANNEL_B, "Channel B"))
        tick()
        watch.recordOpened(card(V1), isShort = false)
        watch.recordPosition(PositionSave(owner, V1, positionMs = 60_000, durationMs = 600_000, ended = false))
        tick()
        watch.recordPosition(PositionSave(owner, V2, positionMs = 10_000, durationMs = null, ended = false))
        watch.recordOpened(card(V2), isShort = true)
        tick()
        watch.recordPosition(PositionSave(owner, V3, positionMs = 600_000, durationMs = 600_000, ended = true))
        tick()
        val mix = checkNotNull(playlists.create("Mix"))
        playlists.add(mix, card(V2))
        tick()
        playlists.add(mix, card(V1))
        tick()
        playlists.create("Empty")
        tick()
        playlists.addToWatchLater(card(V3))
        tick()
        clock.nowMillis = SETTINGS_CHANGED_AT
        settings.update(owner) {
            it.copy(speedPercent = 150, captionsLanguage = "fr", showShorts = true, forYou = false, filterPacks = OWNER_PACKS)
        }
        tick()
        forYou.hideVideo(V2)
        tick()
        forYou.hideChannel(CHANNEL_B)
        val mira = (profiles.create(OwnerPass(), "Mira", ProfileTint.Rose) as ProfileEdit.Done).profileId
        session.select(mira)
        subscriptions.subscribe(channel(CHANNEL_A, "Channel A"))
        forYou.hideVideo(V3)
        filters.updateRestrictions(OwnerPass(), mira) { it.copy(filterPacks = MIRA_RESTRICTED_PACKS) }
        session.select(owner)
    }

    private fun tick() {
        clock.nowMillis += 1_000
    }

    private suspend fun exportedFile(): Uri {
        assertTrue(backups.export(OwnerPass()) is ExportResult.Saved)
        return files.lastUri
    }

    private fun freshDatabase(): KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()

    private suspend fun exportedBundle(): BackupBundle {
        assertTrue(backups.export(OwnerPass()) is ExportResult.Saved)
        return (decodeBackup(files.lastBytes) as BackupRead.Valid).backup.bundle
    }

    private suspend fun opened(uri: Uri): ValidBackup = (backups.open(uri) as BackupRead.Valid).backup

    /** Every profile of [backup] restored with its default choice (Replace when it can, KP-D24). */
    private suspend fun restoreAll(backup: ValidBackup): RestoreResult = backups.restore(OwnerPass(), backup, defaultChoices(backup))

    private suspend fun defaultChoices(backup: ValidBackup): List<RestoreChoice> =
        checkNotNull(backups.restorePlan(backup)).profiles.map { it.defaultChoice }

    private suspend fun validBackup(text: String): ValidBackup = opened(files.put(text.encodeToByteArray()))

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/backup/$name")).use { it.readBytes() }

    private fun channel(url: String, name: String, avatarUrl: String? = null): ChannelItem =
        ChannelItem(name = name, url = url, avatarUrl = avatarUrl, subscriberCount = -1, description = null, verified = false)

    private fun card(videoId: VideoId): VideoItem = VideoItem(
        id = videoId,
        title = "Title ${videoId.value}",
        uploaderName = "Channel A",
        uploaderUrl = CHANNEL_A,
        durationSeconds = 600,
        viewCount = 1_234,
        textualUploadDate = "3 days ago",
        uploadDate = Instant.fromEpochMilliseconds(T0 - DAY),
        thumbnailUrl = "https://img/${videoId.value}",
        isLive = false,
    )
}

/** Files kept in memory: [cutWritesShort] makes a save write half the bytes, as a full disk might. */
private class FakeBackupFiles : BackupFiles {
    val stored = LinkedHashMap<Uri, ByteArray>()
    var cutWritesShort = false
    lateinit var lastUri: Uri
    val lastBytes: ByteArray get() = stored.getValue(lastUri)
    private var next = 0

    fun put(bytes: ByteArray): Uri = Uri.parse("content://test/${next++}").also { stored[it] = bytes }

    override suspend fun save(fileName: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): String? {
        val written = if (cutWritesShort) bytes.copyOf(bytes.size / 2) else bytes
        if (!verify(written)) return null
        lastUri = put(written)
        return fileName
    }

    override suspend fun list(): List<SavedBackup> = error("not used")

    override suspend fun read(uri: Uri, maxBytes: Int): FileRead = when (val bytes = stored[uri]) {
        null -> FileRead.Unreadable
        else -> if (bytes.size > maxBytes) FileRead.TooLarge else FileRead.Bytes(bytes)
    }

    override suspend fun delete(uri: Uri): Boolean = stored.remove(uri) != null
}

private const val T0 = 1_700_000_000_000L
private const val DAY = 86_400_000L
private const val SETTINGS_CHANGED_AT = T0 + 20_000
private const val OCT_4_2026_2330_UTC = 1_791_156_600_000L
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val CHANNEL_C = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
private const val RAYCEVICK_URL = "https://www.youtube.com/channel/UC1JTQBa5QxZCpXrFSkMxmPw"
private const val JOJI_URL = "https://www.youtube.com/channel/UCFl7yKfcRcFmIUbKeCA-SJQ"
private const val COMMA_URL = "https://www.youtube.com/channel/UCq-Fj5jknLsUf-MWSy4_brA"
private val V1 = VideoId("v1aaaaaaaaa")
private val V2 = VideoId("v2bbbbbbbbb")
private val V3 = VideoId("v3ccccccccc")

private val PHONE_DEFAULTS = ProfileSettings(showShorts = true, speedPercent = 125)

/** Two of Islamic content only's channels (`IslamicOnly`): on by default, and off. */
private const val ALAFASY = "UCmMcOjsVehVlEOteyrhjI2Q"
private const val TWO_HOLY_MOSQUES = "UCdAnnrdcG0t8x1qDjVgaIfQ"

/** The owner's own packs and Mira's owner-set ones in [BackupRepositoryTest]'s two profiles. */
private val OWNER_PACKS = FilterPacks(halalOnly = true, stageMagic = true, festivals = true)
private val MIRA_RESTRICTED_PACKS = FilterPacks(
    harmfulForKids = true,
    challengesAndVlogs = true,
    islamicOnly = true,
    islamicChannels = mapOf(ALAFASY to false, TWO_HOLY_MOSQUES to true),
)

private val HAND_WRITTEN_BACKUP = """
    {"bundleVersion":1,"app":"YouTube KE","exportedAtMillis":5,"profiles":[
      {"name":"Yuri","tint":"teal","owner":false,"createdAtMillis":3,"updatedAtMillis":4,
       "subscriptions":[],"history":[],"playlists":[]},
      {"name":"Mira","tint":"chartreuse","owner":true,"createdAtMillis":1,"updatedAtMillis":2,
       "subscriptions":[{"channelUrl":"$CHANNEL_B","name":"From the backup","subscribedAtMillis":111,"updatedAtMillis":222}],
       "history":[{"videoId":"${V1.value}","title":"Watched","positionMs":590000,"durationMs":600000,"watched":true,
                   "lastWatchedAtMillis":333,"updatedAtMillis":444}],
       "playlists":[{"name":"Road trip","createdAtMillis":10,"updatedAtMillis":20,"videos":[
         {"videoId":"${V2.value}","title":"Saved first","durationSeconds":60,"addedAtMillis":30,"updatedAtMillis":31},
         {"videoId":"${V1.value}","title":"Watched","durationSeconds":600,"addedAtMillis":40,"updatedAtMillis":41}]}],
       "watchLater":{"name":"Watch later","createdAtMillis":50,"updatedAtMillis":60,"videos":[
         {"videoId":"${V3.value}","title":"Later","durationSeconds":120,"addedAtMillis":70,"updatedAtMillis":71}]}}
    ]}
""".trimIndent()

private val KIDS_DAY: LocalDate = LocalDate.of(2026, 10, 10)
