package com.arshad.ayn.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [decodeBackup]'s checks (M5-D5/D14) on hand-written files: what restores, and every refusal,
 * each made by breaking one thing in an otherwise valid file.
 */
class BackupCodecTest {

    @Test
    fun aValidBackupNamesItsOwnerAndKeepsItsProfilesInFileOrder() {
        val backup = valid(bundle(profile("Mira", owner = false), profile("Arshad", owner = true)))

        assertEquals("Arshad", backup.ownerName)
        assertEquals(listOf("Mira", "Arshad"), backup.profiles.map { it.name })
        assertEquals(EXPORTED_AT, backup.exportedAtMillis)
    }

    @Test
    fun backupsCarryingEitherAppMarkerAreAccepted() {
        assertEquals("Arshad", valid(bundle(profile("Arshad", owner = true), app = "Ayn")).ownerName)
        assertEquals("Arshad", valid(bundle(profile("Arshad", owner = true), app = "YouTube KE")).ownerName)
    }

    @Test
    fun unknownKeysAndALeadingByteOrderMarkAreAccepted() {
        val profile = profile("Arshad", owner = true, extra = ",\"lockHash\":\"x\"")
        val text = "\uFEFF" + bundle(profile).dropLast(1) + ",\"later\":{\"a\":1}}"

        valid(text)
    }

    @Test
    fun aNewPipeExportIsNotABackup() {
        assertRefused(BackupRefusal.NotABackup, resource("newpipe-subscriptions.json"))
    }

    @Test
    fun aTakeoutCsvIsNotABackup() {
        assertRefused(BackupRefusal.NotABackup, resource("takeout-subscriptions-en.csv"))
    }

    @Test
    fun aFileWithoutAVersionIsNotABackup() {
        assertRefused(BackupRefusal.NotABackup, bundle(profile("Arshad", owner = true)).replace(""""bundleVersion":1,""", ""))
    }

    @Test
    fun anotherAppsMarkerIsNotABackup() {
        assertRefused(BackupRefusal.NotABackup, bundle(profile("Arshad", owner = true), app = "SmartTube"))
    }

    @Test
    fun aFileCutShortIsRefused() {
        val text = bundle(profile("Arshad", owner = true))

        assertRefused(BackupRefusal.NotABackup, text.take(text.length / 2))
    }

    @Test
    fun aNewerVersionIsRefusedAsNewerEvenWhenItsShapeChanged() {
        val newer = """{"bundleVersion":${BUNDLE_VERSION + 1},"app":"Ayn","exportedAtMillis":1,"profiles":{"moved":"elsewhere"}}"""

        assertRefused(BackupRefusal.NewerVersion, newer)
    }

    /** KP-D24: an export may leave the owner out. */
    @Test
    fun aBackupWithoutAnOwnerIsValid() {
        assertEquals(null, valid(bundle(profile("Mira", owner = false))).ownerName)
    }

    @Test
    fun aBackupWithTwoOwnersIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true), profile("Mira", owner = true)))
    }

    @Test
    fun aBackupWithNoProfilesIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle())
    }

    @Test
    fun aBackupWithSixProfilesIsDamaged() {
        val six = listOf(profile("P0", owner = true)) + (1..5).map { profile("P$it", owner = false) }

        assertRefused(BackupRefusal.Damaged, bundle(*six.toTypedArray()))
    }

    @Test
    fun namesThatDifferOnlyInAsciiCaseAreDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("Sam", owner = true), profile("sAM", owner = false)))
    }

    @Test
    fun namesThatDifferOnlyInNonAsciiCaseRestoreAsTheDatabaseKeepsThemApart() {
        valid(bundle(profile("Émile", owner = true), profile("émile", owner = false)))
    }

    @Test
    fun anUntrimmedProfileNameIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("Sam ", owner = true)))
    }

    @Test
    fun aProfileNameOverTwentyCharactersIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("A".repeat(21), owner = true)))
    }

    @Test
    fun aChannelSubscribedTwiceInOneProfileIsDamaged() {
        val twice = """"subscriptions":[${subscription("UCa")},${subscription("UCa")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = twice)))
    }

    @Test
    fun theSameChannelInTwoProfilesIsValid() {
        val one = """"subscriptions":[${subscription("UCa")}]"""

        valid(bundle(profile("Arshad", owner = true, lists = one), profile("Mira", owner = false, lists = one)))
    }

    @Test
    fun aVideoTwiceInHistoryIsDamaged() {
        val twice = """"history":[${watchEntry(V1)},${watchEntry(V1)}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = twice)))
    }

    @Test
    fun aVideoTwiceInAPlaylistIsDamaged() {
        val twice = """"playlists":[${playlist("Mix", savedVideo(V1), savedVideo(V1))}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = twice)))
    }

    @Test
    fun aVideoTwiceInWatchLaterIsDamaged() {
        val extra = ""","watchLater":${playlist("Watch later", savedVideo(V1), savedVideo(V1))}"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = extra)))
    }

    @Test
    fun aBlankPlaylistNameIsDamaged() {
        val blank = """"playlists":[${playlist("  ")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = blank)))
    }

    @Test
    fun aHistoryVideoIdNoVideoCanHaveIsDamaged() {
        val short = """"history":[${watchEntry("v1")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = short)))
    }

    @Test
    fun aSavedVideoIdNoVideoCanHaveIsDamaged() {
        val spaced = """"playlists":[${playlist("Mix", savedVideo("v1aaaaaa aa"))}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = spaced)))
    }

    @Test
    fun aTitleLongerThanAnyRealOneIsDamaged() {
        val long = """"history":[${watchEntry(V1).dropLast(1)},"title":"${"t".repeat(MAX_TEXT_LENGTH + 1)}"}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = long)))
    }

    @Test
    fun theGoldenVersionOneFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v1-golden.json"))

        assertEquals(GOLDEN, backup.bundle)
    }

    @Test
    fun theGoldenVersionTwoFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v2-golden.json"))

        assertEquals(GOLDEN_V2, backup.bundle)
    }

    @Test
    fun theGoldenVersionThreeFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v3-golden.json"))

        assertEquals(GOLDEN_V3, backup.bundle)
    }

    @Test
    fun theGoldenVersionFourFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v4-golden.json"))

        assertEquals(GOLDEN_V4, backup.bundle)
    }

    @Test
    fun theGoldenVersionFiveFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v5-golden.json"))

        assertEquals(GOLDEN_V5, backup.bundle)
    }

    @Test
    fun theGoldenVersionSixFileReadsAsEveryFieldItHolds() {
        val backup = valid(resource("backup-v6-golden.json"))

        assertEquals(GOLDEN_V6, backup.bundle)
    }

    /** M8-D13: from version 6 every profile and own playlist carries its sync id. */
    @Test
    fun aVersionSixFileWithoutAProfilesOrPlaylistsSyncIdIsDamaged() {
        val ids = ""","syncId":"p1""""
        val namedPlaylist = """"playlists":[${playlist("Mix").dropLast(1)},"syncId":"l1"}]"""

        valid(bundle(profile("Arshad", owner = true, lists = namedPlaylist, extra = ids), version = 6))
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = namedPlaylist), version = 6))
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = """"playlists":[${playlist("Mix")}]""", extra = ids), version = 6))
        valid(bundle(profile("Arshad", owner = true, lists = """"playlists":[${playlist("Mix")}]"""), version = 5))
    }

    @Test
    fun aSyncIdTwiceWhereItNamesIsDamaged() {
        val twoPlaylists = """"playlists":[${playlist("A").dropLast(1)},"syncId":"l1"},${playlist("B").dropLast(1)},"syncId":"l1"}]"""

        assertRefused(
            BackupRefusal.Damaged,
            bundle(profile("Arshad", owner = true, extra = ""","syncId":"p1""""), profile("Mira", owner = false, extra = ""","syncId":"p1"""")),
        )
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = twoPlaylists)))
        valid(
            bundle(
                profile("Arshad", owner = true, lists = """"playlists":[${playlist("A").dropLast(1)},"syncId":"l1"}]"""),
                profile("Mira", owner = false, lists = """"playlists":[${playlist("A").dropLast(1)},"syncId":"l1"}]"""),
            ),
        )
    }

    /** M8-D12: `watch-later` is Watch later's id and no other playlist's. */
    @Test
    fun watchLatersSyncIdOnAnotherPlaylistOrAnotherIdOnWatchLaterIsDamaged() {
        val ownWithIt = """"playlists":[${playlist("Mix").dropLast(1)},"syncId":"watch-later"}]"""
        val watchLater = { id: String -> ""","watchLater":${playlist("Watch later").dropLast(1)},"syncId":"$id"}""" }

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, lists = ownWithIt)))
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = watchLater("l1"))))
        valid(bundle(profile("Arshad", owner = true, extra = watchLater("watch-later"))))
    }

    @Test
    fun aRemovalRecordTwiceIsDamaged() {
        val record = { removedAt: Int -> """{"profileSyncId":"p1","kind":"watch","itemKey":"$V1","removedAtMillis":$removedAt}""" }
        val withRemovals = { records: String ->
            bundle(profile("Arshad", owner = true, extra = ""","syncId":"p1""""), version = 6).dropLast(1) + ""","removals":[$records]}"""
        }

        assertRefused(BackupRefusal.Damaged, withRemovals("${record(1)},${record(2)}"))
        valid(withRemovals(record(1)))
    }

    /** FPK-D21: a map longer than any backup holds, or with a key longer than any real one, is
     *  refused, whichever map it is; restore would list each unknown key as its own skip. */
    @Test
    fun aMapLongerThanAnyBackupHoldsIsDamaged() {
        fun keys(count: Int, value: String): String = (1..count).joinToString(",", "{", "}") { "\"k$it\":$value" }
        val settingsPacks = { switches: String -> ",\"settings\":" + settings().dropLast(1) + ",\"filterPacks\":{\"islamicChannels\":$switches}}" }
        val restrictions = { extra: String ->
            ""","restrictions":{"restrictedMode":"off","hideShorts":false,"hideLive":false,"hideMembersOnly":false,"updatedAtMillis":1$extra}"""
        }
        val tooMany = MAX_MAP_ENTRIES + 1
        val longKey = """{"${"k".repeat(MAX_TEXT_LENGTH + 1)}":true}"""
        val refused = listOf(
            ",\"settings\":" + settings(actions = keys(tooMany, "\"skip\"")),
            settingsPacks(keys(tooMany, "true")),
            settingsPacks(longKey),
            restrictions(",\"dailyLimitMinutes\":${keys(tooMany, "30")}"),
            restrictions(",\"filterPacks\":{\"islamicChannels\":${keys(tooMany, "true")}}"),
        )

        refused.forEach { extra -> assertRefused(BackupRefusal.Damaged, bundle(profile("Mira", owner = false, extra = extra), version = 5)) }
        valid(bundle(profile("Mira", owner = false, extra = settingsPacks(keys(MAX_MAP_ENTRIES, "true"))), version = 5))
    }

    /** PAF-D6: the Short mark joined version 4 unbumped; the golden files (without it) read as not Shorts. */
    @Test
    fun anEntryPlayedAsAShortKeepsItsMarkWhichIsLeftOutWhenNotOne() {
        val history = """"history":[${watchEntry("aaaaaaaaaaa").dropLast(1)},"short":true},${watchEntry("bbbbbbbbbbb")}]"""
        val backup = valid(bundle(profile("Arshad", owner = true, lists = history), version = 4))

        assertEquals(listOf(true, false), backup.bundle.profiles.single().history.map { it.short })
        val written = encodeBackup(backup.bundle).decodeToString()
        assertEquals(1, Regex("\"short\"").findAll(written).count())
        assertEquals(backup.bundle, valid(written).bundle)
    }

    /** KP-D14: the owner is never a kids profile. */
    @Test
    fun aKidsOwnerIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = ",\"kids\":true"), version = 4))
    }

    /** KP-D17: a rule a store would refuse is left out at restore, not a reason to refuse the file. */
    @Test
    fun rulesThisBuildCantUseStillRestore() {
        val rules = ""","rules":[${rule("topic", "news")},${rule("video", "short")},${rule("keyword", "prank")},${rule("keyword", "prank")}]"""

        valid(bundle(profile("Arshad", owner = true, extra = rules), version = 4))
    }

    @Test
    fun aRuleValueLongerThanAnyRealOneIsDamaged() {
        val long = ""","rules":[${rule("keyword", "k".repeat(MAX_TEXT_LENGTH + 1))}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = long), version = 4))
    }

    @Test
    fun aVideoHiddenTwiceInOneProfileIsDamaged() {
        val twice = ""","hiddenVideos":[${hidden("v1aaaaaaaaa")},${hidden("v1aaaaaaaaa")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = twice), version = 3))
    }

    @Test
    fun aHiddenVideoIdNoVideoCanHaveIsDamaged() {
        val bad = ""","hiddenVideos":[${hidden("short")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = bad), version = 3))
    }

    /** Restore stores a hidden channel by its canonical URL, where these two forms are one key. */
    @Test
    fun oneChannelHiddenUnderTwoUrlFormsIsDamaged() {
        val url = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
        val twice = ""","hiddenChannels":[${hiddenChannel(url)},${hiddenChannel("$url/videos")}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = twice), version = 3))
    }

    @Test
    fun aHiddenChannelUrlLongerThanAnyRealOneIsDamaged() {
        val long = ""","hiddenChannels":[${hiddenChannel("https://www.youtube.com/@" + "a".repeat(MAX_TEXT_LENGTH))}]"""

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = long), version = 3))
    }

    @Test
    fun settingValuesThisBuildDoesntKnowStillRestore() {
        val odd = settings(speedPercent = 110, captionsLanguage = "not a tag", actions = """{"sponsor":"mute","chapter":"skip"}""")

        valid(bundle(profile("Arshad", owner = true, extra = ",\"settings\":$odd"), version = 2))
    }

    @Test
    fun aSettingOfTheWrongTypeIsDamaged() {
        val quotedSpeed = settings(speedPercent = 100).replace("\"speedPercent\":100", "\"speedPercent\":\"fast\"")
        val numberAction = settings(actions = """{"sponsor":1}""")

        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = ",\"settings\":$quotedSpeed"), version = 2))
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true, extra = ",\"settings\":$numberAction"), version = 2))
    }

    @Test
    fun aWrongTypeIsDamaged() {
        assertRefused(BackupRefusal.Damaged, bundle(profile("Arshad", owner = true)).replace(""""owner":true""", """"owner":"yes""""))
    }

    @Test
    fun nestingDeeperThanAnyBackupIsRefusedEvenUnderAKeyTheDecoderWouldSkip() {
        val deep = "[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH)
        val text = bundle(profile("Arshad", owner = true)).dropLast(1) + ",\"later\":$deep}"

        assertRefused(BackupRefusal.NotABackup, text)
    }

    @Test
    fun aFileLargerThanTheCapIsRefusedUnread() {
        assertEquals(BackupRead.Refused(BackupRefusal.TooLarge), decodeBackup(ByteArray(MAX_BACKUP_BYTES + 1)))
    }

    private fun valid(text: String): ValidBackup {
        val read = decodeBackup(text.encodeToByteArray())
        assertTrue("expected a valid backup, got $read", read is BackupRead.Valid)
        return (read as BackupRead.Valid).backup
    }

    private fun assertRefused(reason: BackupRefusal, text: String) {
        assertEquals(BackupRead.Refused(reason), decodeBackup(text.encodeToByteArray()))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/$name")).use { it.readBytes().decodeToString() }
}

private const val EXPORTED_AT = 1_759_600_000_000L
private const val V1 = "v1aaaaaaaaa"

/** `backup-v1-golden.json`, field by field. */
private val GOLDEN = BackupBundle(
    bundleVersion = 1,
    app = "YouTube KE",
    exportedAtMillis = 1_759_600_000_000,
    profiles = listOf(
        BackupProfile(
            name = "Arshad",
            tint = "teal",
            owner = true,
            createdAtMillis = 1_700_000_000_000,
            updatedAtMillis = 1_700_000_001_000,
            subscriptions = listOf(
                BackupSubscription(
                    "https://www.youtube.com/channel/UC1JTQBa5QxZCpXrFSkMxmPw", "Raycevick", "https://yt3.example/r",
                    1_700_000_002_000, 1_700_000_003_000,
                ),
                BackupSubscription("https://www.youtube.com/channel/UCFl7yKfcRcFmIUbKeCA-SJQ", "Joji", null, 1_700_000_004_000, 1_700_000_005_000),
            ),
            history = listOf(
                BackupWatchEntry(
                    "dQw4w9WgXcQ", "Never Gonna", "Rick", "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
                    "https://i.example/d.jpg", 120_000, 213_000, false, 1_700_000_006_000, 1_700_000_007_000,
                ),
                BackupWatchEntry("jNQXAC9IVRw", null, null, null, null, 0, null, true, 1_700_000_008_000, 1_700_000_009_000),
            ),
            playlists = listOf(
                BackupPlaylist(
                    "Road trip", 1_700_000_010_000, 1_700_000_011_000,
                    listOf(
                        BackupPlaylistVideo(
                            "jNQXAC9IVRw", "Me at the zoo", "jawed", "https://www.youtube.com/channel/UC4QobU6STFB0P71PMvOGN5A",
                            19, 1_114_000_000_000, "https://i.example/z.jpg", 1_700_000_012_000, 1_700_000_013_000,
                        ),
                        BackupPlaylistVideo("dQw4w9WgXcQ", "Never Gonna", null, null, -1, null, null, 1_700_000_014_000, 1_700_000_015_000),
                    ),
                ),
            ),
            watchLater = BackupPlaylist(
                "Watch later", 1_700_000_016_000, 1_700_000_017_000,
                listOf(BackupPlaylistVideo("9bZkp7q19f0", "Gangnam Style", "officialpsy", null, 253, null, null, 1_700_000_018_000, 1_700_000_019_000)),
            ),
        ),
        BackupProfile("Mira", "rose", false, 1_700_000_020_000, 1_700_000_021_000, emptyList(), emptyList(), emptyList()),
    ),
)

/** `backup-v2-golden.json`, field by field: the owner holds settings, Mira is on the defaults. */
private val GOLDEN_V2 = BackupBundle(
    bundleVersion = 2,
    app = "Ayn",
    exportedAtMillis = 1_791_300_000_000,
    profiles = listOf(
        BackupProfile(
            name = "Arshad",
            tint = "teal",
            owner = true,
            createdAtMillis = 1_700_000_000_000,
            updatedAtMillis = 1_700_000_001_000,
            subscriptions = listOf(
                BackupSubscription(
                    "https://www.youtube.com/channel/UC1JTQBa5QxZCpXrFSkMxmPw", "Raycevick", "https://yt3.example/r",
                    1_700_000_002_000, 1_700_000_003_000,
                ),
            ),
            history = emptyList(),
            playlists = emptyList(),
            settings = BackupSettings(
                speedPercent = 125,
                resume = false,
                captionsLanguage = "en-GB",
                sponsorBlock = true,
                segmentActions = mapOf(
                    "sponsor" to "skip", "selfpromo" to "skip", "interaction" to "off", "intro" to "bar", "outro" to "button",
                    "preview" to "button", "hook" to "bar", "music_offtopic" to "off", "filler" to "button",
                ),
                skipNote = false,
                dearrowTitles = true,
                dearrowThumbnails = false,
                dislikes = true,
                showShorts = true,
                updatedAtMillis = 1_791_200_000_000,
            ),
        ),
        BackupProfile("Mira", "rose", false, 1_700_000_020_000, 1_700_000_021_000, emptyList(), emptyList(), emptyList()),
    ),
)

/** `backup-v3-golden.json`, field by field: v2's golden plus For you off and hidden videos and a
 *  channel for the owner, one hidden video for Mira. */
private val GOLDEN_V3 = GOLDEN_V2.copy(
    bundleVersion = 3,
    exportedAtMillis = 1_791_400_000_000,
    profiles = listOf(
        GOLDEN_V2.profiles[0].copy(
            settings = GOLDEN_V2.profiles[0].settings?.copy(forYou = false),
            hiddenVideos = listOf(BackupHiddenVideo("dQw4w9WgXcQ", 1_791_350_000_000), BackupHiddenVideo("njX2bu-_Vw4", 1_791_360_000_000)),
            hiddenChannels = listOf(BackupHiddenChannel("https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA", 1_791_370_000_000)),
        ),
        GOLDEN_V2.profiles[1].copy(hiddenVideos = listOf(BackupHiddenVideo("JsBZOcqZerk", 1_791_380_000_000))),
    ),
)

/** `backup-v4-golden.json`, field by field: the owner's own filters and a keyword; Zainab, a kids
 *  profile with the owner's restrictions, one approved channel (a subscription), an approved video
 *  and a blocked one. */
private val GOLDEN_V4 = BackupBundle(
    bundleVersion = 4,
    app = "Ayn",
    exportedAtMillis = 1_791_500_000_000,
    profiles = listOf(
        BackupProfile(
            name = "Arshad",
            tint = "amber",
            owner = true,
            createdAtMillis = 1_700_000_000_000,
            updatedAtMillis = 1_700_000_001_000,
            subscriptions = emptyList(),
            history = emptyList(),
            playlists = emptyList(),
            settings = BackupSettings(
                speedPercent = 100,
                resume = true,
                sponsorBlock = true,
                segmentActions = mapOf("sponsor" to "button"),
                skipNote = true,
                dearrowTitles = true,
                dearrowThumbnails = true,
                dislikes = true,
                showShorts = false,
                updatedAtMillis = 1_791_400_000_000,
                restrictedMode = "strict",
                hideLive = true,
                maxLengthMinutes = 60,
            ),
            rules = listOf(BackupRule(kind = "keyword", value = "prank", byOwner = false, addedAtMillis = 1_791_410_000_000)),
        ),
        BackupProfile(
            name = "Zainab",
            tint = "violet",
            owner = false,
            createdAtMillis = 1_700_000_020_000,
            updatedAtMillis = 1_700_000_021_000,
            subscriptions = listOf(
                BackupSubscription("https://www.youtube.com/channel/UCVzLLZkDuFGAE2BGdBuBNBg", "Bluey", null, 1_791_420_000_000, 1_791_420_000_000),
            ),
            history = emptyList(),
            playlists = emptyList(),
            kids = true,
            restrictions = BackupRestrictions(
                restrictedMode = "strict",
                hideShorts = true,
                hideLive = true,
                hideMembersOnly = true,
                dailyLimitMinutes = mapOf("monday" to 60, "saturday" to 90),
                bedtimeStartMinute = 1_170,
                bedtimeEndMinute = 420,
                updatedAtMillis = 1_791_430_000_000,
            ),
            rules = listOf(
                BackupRule(
                    kind = "approved_video",
                    value = "JsBZOcqZerk",
                    byOwner = true,
                    title = "Extraordinary Wildlife Of The Arctic",
                    channelName = "BBC Earth",
                    channelUrl = "https://www.youtube.com/channel/UCwmZiChSryoWQCZMIQezgTg",
                    thumbnailUrl = "https://i.ytimg.com/vi/JsBZOcqZerk/mqdefault.jpg",
                    durationSeconds = 1_943,
                    addedAtMillis = 1_791_440_000_000,
                ),
                BackupRule(kind = "video", value = "dQw4w9WgXcQ", byOwner = true, title = "Never Gonna", addedAtMillis = 1_791_450_000_000),
            ),
        ),
    ),
)

/** `backup-v5-golden.json`, field by field: v4's golden plus the owner's own Halal only with loot
 *  boxes, and Zainab's owner-set Harmful for kids (with Overstimulating) and Islamic content only
 *  with two switches moved. */
private val GOLDEN_V5 = GOLDEN_V4.copy(
    bundleVersion = 5,
    exportedAtMillis = 1_791_600_000_000,
    profiles = listOf(
        GOLDEN_V4.profiles[0].copy(
            settings = GOLDEN_V4.profiles[0].settings?.copy(filterPacks = BackupFilterPacks(halalOnly = true, lootBoxes = true)),
        ),
        GOLDEN_V4.profiles[1].copy(
            restrictions = GOLDEN_V4.profiles[1].restrictions?.copy(
                filterPacks = BackupFilterPacks(
                    harmfulForKids = true,
                    overstimulating = true,
                    islamicOnly = true,
                    islamicChannels = mapOf("UCmMcOjsVehVlEOteyrhjI2Q" to false, "UCdAnnrdcG0t8x1qDjVgaIfQ" to true),
                ),
            ),
        ),
    ),
)

/** `backup-v6-golden.json`, field by field: v5's golden plus sync ids, a playlist, an empty Watch
 *  later and a removal record. */
private val GOLDEN_V6 = GOLDEN_V5.copy(
    bundleVersion = 6,
    exportedAtMillis = 1_791_700_000_000,
    profiles = listOf(
        GOLDEN_V5.profiles[0].copy(
            syncId = "6f1c2b9e-3d4a-4f5b-8c7d-0e1f2a3b4c5d",
            playlists = listOf(
                BackupPlaylist("Road trip", 1_791_650_000_000, 1_791_650_001_000, emptyList(), syncId = "a1b2c3d4-e5f6-4789-9abc-def012345678"),
            ),
            watchLater = BackupPlaylist("Watch later", 1_791_660_000_000, 1_791_660_000_000, emptyList(), syncId = "watch-later"),
        ),
        GOLDEN_V5.profiles[1].copy(syncId = "0d9e8f7a-6b5c-4d3e-9f2a-1b0c9d8e7f6a"),
    ),
    removals = listOf(
        BackupRemoval(
            "0d9e8f7a-6b5c-4d3e-9f2a-1b0c9d8e7f6a", "subscription", "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", 1_791_690_000_000,
        ),
    ),
)

private fun bundle(vararg profiles: String, app: String = "Ayn", version: Int = 1): String =
    """{"bundleVersion":$version,"app":"$app","exportedAtMillis":$EXPORTED_AT,"profiles":[${profiles.joinToString(",")}]}"""

/** [lists] replaces one of the three lists, e.g. `"history":[…]`; [extra] appends keys. */
private fun profile(name: String, owner: Boolean, lists: String = "", extra: String = ""): String {
    val defaults = listOf(""""subscriptions":[]""", """"history":[]""", """"playlists":[]""")
        .filterNot { lists.isNotEmpty() && lists.startsWith(it.substringBefore(':')) }
    val all = (defaults + listOfNotNull(lists.takeIf { it.isNotEmpty() })).joinToString(",")
    return """{"name":"$name","tint":"amber","owner":$owner,"createdAtMillis":1,"updatedAtMillis":2,$all$extra}"""
}

private fun subscription(channelId: String): String =
    """{"channelUrl":"https://www.youtube.com/channel/$channelId","name":"C","subscribedAtMillis":1,"updatedAtMillis":1}"""

private fun watchEntry(videoId: String): String =
    """{"videoId":"$videoId","positionMs":0,"watched":false,"lastWatchedAtMillis":1,"updatedAtMillis":1}"""

private fun playlist(name: String, vararg videos: String): String =
    """{"name":"$name","createdAtMillis":1,"updatedAtMillis":1,"videos":[${videos.joinToString(",")}]}"""

private fun hidden(videoId: String): String = """{"videoId":"$videoId","hiddenAtMillis":1}"""

private fun hiddenChannel(url: String): String = """{"channelUrl":"$url","hiddenAtMillis":1}"""

private fun rule(kind: String, value: String): String = """{"kind":"$kind","value":"$value","byOwner":true,"addedAtMillis":1}"""

private fun savedVideo(videoId: String): String =
    """{"videoId":"$videoId","title":"T","durationSeconds":60,"addedAtMillis":1,"updatedAtMillis":1}"""

/** A settings object with the given values, the rest the defaults'. */
private fun settings(speedPercent: Int = 100, captionsLanguage: String? = null, actions: String = "{}"): String =
    """{"speedPercent":$speedPercent,"resume":true,${captionsLanguage?.let { "\"captionsLanguage\":\"$it\"," } ?: ""}""" +
        """"sponsorBlock":true,"segmentActions":$actions,"skipNote":true,"dearrowTitles":true,"dearrowThumbnails":true,""" +
        """"dislikes":true,"showShorts":false,"updatedAtMillis":1}"""
