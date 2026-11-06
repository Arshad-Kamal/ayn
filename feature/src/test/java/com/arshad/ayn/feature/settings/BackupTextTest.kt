package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.RestoredProfile
import com.arshad.ayn.core.backup.SettingSkip
import com.arshad.ayn.core.store.ProfileTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Backup & restore text both UIs show, against `kp-backup` v1 and the branches the mockups don't draw. */
class BackupTextTest {

    @Test
    fun aKidsProfilesCountsNameItsApprovedChannelsAndVideos() {
        assertEquals("6 approved channels · 1 approved video · 41 in history", backupCounts(summary("Zainab", kids = true)))
        assertEquals("6 subscriptions · 41 in history", backupCounts(summary("Mira")))
    }

    @Test
    fun theRestorePickCountsWhatItReplacesAndAdds() {
        val replace = row(RestoreChoice.Replace(2))
        val add = row(RestoreChoice.AddAsNew)
        assertEquals("Replaces 4 profiles, adds 1", restorePickNote(List(4) { replace } + add))
        assertEquals("Adds 2 profiles", restorePickNote(listOf(add, add, row(RestoreChoice.Skip))))
        assertNull(restorePickNote(listOf(row(RestoreChoice.Skip))))
    }

    @Test
    fun aSettingsRestoreSaysWhichKeptTheirLocks() {
        val result = restored(restoredProfile("Arshad", replaced = true), restoredProfile("Mira", replaced = true), restoredProfile("Guests", replaced = false))
        assertEquals(
            "3 profiles from October 8. The 2 you replaced keep their locks here; Guests, added as new, has none.",
            restoredLede(result, "October 8"),
        )
        assertEquals(
            "1 profile from October 8. Guests, added as new, has no lock.",
            restoredLede(restored(restoredProfile("Guests", replaced = false)), "October 8"),
        )
    }

    @Test
    fun aPartialRestoreCountsTheSettingsThatKeptTheirValues() {
        val skip = SettingSkip("speedPercent", "225", "125")
        val result = restored(restoredProfile("Mira", replaced = true, skip), restoredProfile("Yuri", replaced = true, skip))
        assertEquals(
            "Mira and Yuri came back from October 8. Two of their settings don’t work in this version of Ayn, so those kept their values here:",
            restoredLede(result, "October 8"),
        )
        assertEquals("Not everything was restored", restoredTitle(result))
    }

    @Test
    fun firstLaunchsResultsAskForTheOwnersLockWhenAKidWaits() {
        val result = restored(restoredProfile("Arshad", replaced = false), restoredProfile("Zainab", replaced = false))
        assertEquals(
            "2 profiles from October 8, all unlocked. Lock your profile first: Zainab’s kids profile waits for it.",
            firstRestoredLede(result, "October 8", kids = listOf("Zainab")),
        )
    }

    @Test
    fun skippedValuesAreNamedWithWhatWasKept() {
        val speed = skipText(SettingSkip("speedPercent", "225", "125"))
        assertEquals("Playback speed", speed.label)
        assertEquals("2.25× in the backup, and Ayn goes up to 2×. Kept 1.25×.", speed.detail)
        val hook = skipText(SettingSkip("segmentActions.hook", "mute", "bar"))
        assertEquals("SponsorBlock › Hook and greetings", hook.label)
        assertEquals("“Mute” isn’t a choice in this version. Kept Show on the seek bar only.", hook.detail)
        val limit = skipText(SettingSkip("restrictions.dailyLimitMinutes.monday", "2000", null))
        assertEquals("Restrictions › Daily limit › Monday", limit.label)
        assertEquals("2000 min in the backup doesn’t work in this version of Ayn. Kept No limit.", limit.detail)
        assertEquals(
            "This version of Ayn doesn’t have this category, so it wasn’t restored.",
            skipText(SettingSkip("segmentActions.newcat", "skip", null)).detail,
        )
    }

    /** LIVE-D21: a place for chat this build doesn't offer is quoted; the kept one is named as its row names it. */
    @Test
    fun aChatPlacementThisVersionDoesntOfferIsNamedWithTheOneKept() {
        val chat = skipText(SettingSkip("chatPlacement", "centre", "top-left"))

        assertEquals("Live chat", chat.label)
        assertEquals("“Centre” in the backup doesn’t work in this version of Ayn. Kept Top-left corner.", chat.detail)
    }

    /** FPK-D21: a switch for a channel this build doesn't list, the profile's own or the owner's. */
    @Test
    fun anIslamicChannelThisVersionDoesntListIsNamedByItsId() {
        val own = skipText(SettingSkip("filterPacks.islamicChannels.UCgoneeeeeeeeeeeeeeeeeee", "true", null))
        val owners = skipText(SettingSkip("restrictions.filterPacks.islamicChannels.UCgoneeeeeeeeeeeeeeeeeee", "false", null))

        assertEquals("Islamic content only › UCgoneeeeeeeeeeeeeeeeeee", own.label)
        assertEquals("Restrictions › Islamic content only › UCgoneeeeeeeeeeeeeeeeeee", owners.label)
        assertEquals("This version of Ayn doesn’t list this channel, so it wasn’t restored.", owners.detail)
    }

    @Test
    fun anImportReportsWhatWasAlreadyThere() {
        assertEquals("Nothing new" to "all 27 channels were already in your subscriptions", importedText(added = 0, alreadySubscribed = 27))
        assertEquals("Nothing new" to "the 1 channel was already in your subscriptions", importedText(added = 0, alreadySubscribed = 1))
        assertEquals("23 channels added" to null, importedText(added = 23, alreadySubscribed = 0))
    }

    @Test
    fun anUnreadSavedBackupShowsItsDateOnly() {
        assertEquals("saved October 3", savedHint(null, "October 3"))
        assertEquals("4 profiles · saved October 3", savedHint(4, "October 3"))
    }

    private fun summary(name: String, owner: Boolean = false, kids: Boolean = false) =
        BackupProfileSummary(name, ProfileTint.Amber, owner, kids, ProfileCounts(6, 41, 0, 0, 0, if (kids) 1 else 0))

    private fun row(choice: RestoreChoice) = RestoreRow(summary("Mira"), replaces = 2, choice = choice)

    private fun restoredProfile(name: String, replaced: Boolean, vararg skips: SettingSkip) =
        RestoredProfile(0, name, replaced, ProfileCounts(0, 0, 0, 0, 0, 0), settingsApplied = 24 - skips.size, settingsTotal = 24, skips = skips.toList())

    private fun restored(vararg profiles: RestoredProfile) = RestoreResult.Restored(ownerId = 1, profiles = profiles.toList())
}
