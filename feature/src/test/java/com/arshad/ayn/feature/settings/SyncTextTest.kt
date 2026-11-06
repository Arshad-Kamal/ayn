package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Sync text both UIs show, against `m8-sync-tv`/`m8-sync-phone` v1–v4 and the branches
 *  the mockups don't draw. Times in UTC; now is 8:30 PM. */
class SyncTextTest {

    @Test
    fun theStatusLineSaysSyncingTheLastSyncOrSinceWhenItFails() {
        assertEquals("Syncing…", syncStatusLine(ON.copy(syncing = true), TIME))
        assertEquals("Synced 2 minutes ago", syncStatusLine(ON, TIME))
        assertEquals("Not synced since 6:40 PM", syncStatusLine(ON.copy(lastSyncedMillis = AT_6_40, failure = SyncFailure.NO_CONNECTION), TIME))
        assertEquals("Not synced yet", syncStatusLine(SyncStatus(linked = true), TIME))
    }

    @Test
    fun settingsSyncRowSaysItsStateOrWhoCanSetItUp() {
        assertEquals("Only the owner can set up sync", syncCategoryHint(isOwner = false, ON, TV, TIME))
        assertEquals("Off · keep your TV and phone the same", syncCategoryHint(isOwner = true, SyncStatus(linked = false), TV, TIME))
        assertEquals("Off · keep your TV and phone the same", syncCategoryHint(isOwner = true, ON.copy(awaitingMatch = true), TV, TIME))
        assertEquals("On · synced 2 minutes ago", syncCategoryHint(isOwner = true, ON, TV, TIME))
    }

    @Test
    fun aRemovedNextcloudSignInAsksToSignInAgainAndSaysNothingChanged() {
        assertEquals(
            "Nextcloud says Ayn’s access was removed." to "Sign in again to keep syncing. Nothing on this Shield has changed.",
            syncFailureNotice(SyncFailure.SIGN_IN_REJECTED, NEXTCLOUD, TV),
        )
        assertEquals(SyncFix.SignInAgain, syncFix(SyncFailure.SIGN_IN_REJECTED))
        assertEquals(SyncFix.LinkAgain, syncFix(SyncFailure.LINK_LOST))
        assertEquals(null, syncFix(SyncFailure.NO_CONNECTION))
    }

    @Test
    fun aJoiningPassphraseNamesTheDevicesThereAndTheLatestSync() {
        assertEquals(
            "The Ayn folder already holds sync files from Pixel 7 Pro, last synced today at 8:12 PM. Enter the passphrase you chose there.",
            joinPassphraseBody(listOf(PIXEL), TV, TIME, NEXTCLOUD),
        )
        assertEquals("Enter the passphrase this Shield synced with before.", joinPassphraseBody(emptyList(), TV, TIME, NEXTCLOUD))
    }

    @Test
    fun turnOnNamesGoogleDriveOnlyWhenThisBuildCanSignInToIt() {
        assertEquals("Ayn keeps its sync files in your Google Drive or on your own Nextcloud", turnOnHint(drive = true))
        assertEquals("Ayn keeps its sync files on your own Nextcloud or another WebDAV server", turnOnHint(drive = false))
    }

    @Test
    fun googleDrivesPassphraseScreensNameGoogleAndTheDrive() {
        assertEquals("Google Drive · owner@example.org", passphraseKicker(DRIVE))
        assertEquals(
            "Ayn locks your sync files with it before they leave this Shield, so Google can’t read them. You’ll enter it once on each " +
                "device. Ayn can’t recover it: if it’s lost, turn sync off and on again with a new one.",
            newPassphraseBody(TV, DRIVE),
        )
        assertEquals(
            "Your Drive already holds sync files from Pixel 7 Pro, last synced today at 8:12 PM. Enter the passphrase you chose there.",
            joinPassphraseBody(listOf(PIXEL), TV, TIME, DRIVE),
        )
        assertEquals("Google Drive · first sync on this Shield", matchKicker(DRIVE, TV))
    }

    @Test
    fun googleDrivesPageNamesTheAccountAndTheAynSyncFolder() {
        assertEquals("owner@example.org · 2.4 MB in the Ayn sync folder", accountHint(DRIVE, 2_500_000))
        assertEquals("As owner@example.org", signInAgainHint(DRIVE))
        assertEquals("Your TV joins when you turn on sync there with the same Google account and passphrase.", soloNote(PHONE, DRIVE))
        assertEquals(
            "Every profile stays on this Shield as it is now. This Shield’s sync file is removed from your Drive, and Pixel 7 Pro keeps " +
                "syncing on its own.",
            turnOffSub(TV, listOf(PIXEL), SyncService.GOOGLE_DRIVE),
        )
    }

    @Test
    fun aRemovedGoogleSignInSaysGoogleRemovedAynsAccess() {
        assertEquals(
            "Google says Ayn’s access was removed." to "Sign in again to keep syncing. Nothing on this Shield has changed.",
            syncFailureNotice(SyncFailure.SIGN_IN_REJECTED, DRIVE, TV),
        )
        assertEquals("Google removed Ayn’s access", launchReason(SyncFailure.SIGN_IN_REJECTED, DRIVE))
        assertEquals("Google Drive is full.", syncFailureNotice(SyncFailure.STORAGE_FULL, DRIVE, TV).first)
        assertEquals("The Ayn sync folder is gone from Google Drive.", syncFailureNotice(SyncFailure.FOLDER_MISSING, DRIVE, TV).first)
    }

    @Test
    fun googlesSignInSaysHowLongTheCodeWorksAndWhereToEnterIt() {
        assertEquals("Waiting for you to allow Ayn · the code works for 30 minutes", googleWaiting(30))
        assertEquals("google.com/device", DriveCode("GQVQ-JKEC", "https://www.google.com/device", 30).page)
        assertEquals("That’s another Google account. Sign in as owner@example.org", googleProblemText(SetupProblem.OTHER_ACCOUNT, DRIVE))
        assertEquals("Ayn wasn’t allowed on Google’s page. Try again to start over", googleProblemText(SetupProblem.DENIED, null))
        assertEquals("The code ran out of time. Try again for a new one", googleProblemText(SetupProblem.EXPIRED, null))
        assertEquals("Ayn couldn’t reach Google. Check your connection and try again", googleProblemText(SetupProblem.NO_CONNECTION, null))
        assertEquals("Your Google Drive is full, so Ayn couldn’t make its folder there", googleProblemText(SetupProblem.STORAGE_FULL, null))
        assertEquals("Google answered with an error. Try again later", googleProblemText(SetupProblem.SERVER_ERROR, null))
    }

    @Test
    fun aWrongPassphraseNamesTheOneDevicesFiles() {
        assertEquals("That passphrase doesn’t open Pixel 7 Pro’s sync files. Check for a typo and try again.", wrongPassphraseText(listOf(PIXEL)))
        assertEquals(
            "That passphrase doesn’t open the sync files there. Check for a typo and try again.",
            wrongPassphraseText(listOf(PIXEL, SyncDevice("sm-x200-0a0b0c0d"))),
        )
    }

    @Test
    fun otherDevicesSayWhenTheyLastSynced() {
        assertEquals("Synced today at 8:12 PM", deviceHint(PIXEL, TIME))
        assertEquals("Synced yesterday at 8:30 PM", deviceHint(SyncDevice("pixel-7-pro-1a2b3c4d", NOW_MILLIS - 24.hours.inWholeMilliseconds), TIME))
        assertEquals(null, deviceHint(SyncDevice("pixel-7-pro-1a2b3c4d"), TIME))
    }

    @Test
    fun thisDeviceSaysWhenItLastSyncedOnceSyncsFail() {
        assertEquals("Last synced at 6:40 PM", thisDeviceHint(ON.copy(lastSyncedMillis = AT_6_40, failure = SyncFailure.NO_CONNECTION), TIME))
        assertEquals("Synced 2 minutes ago", thisDeviceHint(ON, TIME))
        assertEquals("Syncing now", thisDeviceHint(ON.copy(syncing = true), TIME))
    }

    @Test
    fun theServiceRowSaysWhoWhereAndTheFoldersSize() {
        assertEquals("arshad on cloud.example.org · 2.4 MB in the Ayn folder", accountHint(NEXTCLOUD, 2_500_000))
        assertEquals("arshad on cloud.example.org · the Ayn folder", accountHint(NEXTCLOUD, null))
        assertEquals("640 KB", sizeText(655_360))
    }

    @Test
    fun matchSaysWhatEachRowDoesAndTheRoomLeft() {
        val match = ProfileMatch.of(PREVIEW)

        assertEquals("With Mira on Pixel 7 Pro", hereValue(match, MIRA_HERE))
        assertEquals("Same name", hereHint(match, MIRA_HERE))
        assertEquals("The owner always matches the owner", hereHint(match, OWNER_HERE))
        assertEquals("Syncs as a new profile", hereValue(match, YURI_HERE))
        assertEquals("Not on Pixel 7 Pro. It appears there at its next sync", hereHint(match, YURI_HERE))
        assertEquals("Add to this Shield", thereValue(match, GUESTS, TV))
        assertEquals("18 in history · This Shield will have 5 profiles, the most", thereHint(match, GUESTS, TV))
        assertEquals("Matches 2, adds Yuri and Kai to sync, adds Guests here", matchNote(match))
        assertEquals("Pixel 7 Pro syncs 3 profiles. A profile matched with one there becomes one profile on both: their " +
            "subscriptions, history and playlists are put together, nothing is replaced.", matchLede(PREVIEW))
    }

    @Test
    fun toastsSayWhatTurningOnOrOffDid() {
        assertEquals("Sync is on" to "5 profiles match Pixel 7 Pro", syncToastText(SyncToast.TurnedOn(5, listOf("Pixel 7 Pro")), TV))
        assertEquals("Sync is on" to "turn it on on your phone too", syncToastText(SyncToast.TurnedOn(), TV))
        assertEquals("Sync is off" to "This Shield’s file couldn’t be removed from the Ayn folder", syncToastText(SyncToast.TurnedOff(true), TV))
        assertEquals(
            "Sync is off" to "This Shield’s file couldn’t be removed from your Drive",
            syncToastText(SyncToast.TurnedOff(true, SyncService.GOOGLE_DRIVE), TV),
        )
    }

    @Test
    fun turningOffSaysTheOtherDeviceKeepsSyncing() {
        assertEquals(
            "Every profile stays on this Shield as it is now. This Shield’s sync file is removed from the Ayn folder, and Pixel 7 Pro " +
                "keeps syncing on its own.",
            turnOffSub(TV, listOf(PIXEL), SyncService.NEXTCLOUD),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-31T20:30:00Z")
        val NOW_MILLIS = NOW.toEpochMilliseconds()
        val AT_6_40 = (NOW - 1.hours - 50.minutes).toEpochMilliseconds()
        val TIME = SyncTime(NOW, ZoneOffset.UTC, Locale.US)
        val TV = SyncWords(here = "this Shield", both = "TV and phone", otherKind = "phone")
        val PHONE = SyncWords(here = "this phone", both = "phone and TV", otherKind = "TV")
        val NEXTCLOUD = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad")
        val DRIVE = SyncAccount(SyncService.GOOGLE_DRIVE, "", "owner@example.org")
        val ON = SyncStatus(linked = true, lastSyncedMillis = (NOW - 2.minutes).toEpochMilliseconds(), account = NEXTCLOUD)
        val PIXEL = SyncDevice("pixel-7-pro-1a2b3c4d", (NOW - 18.minutes).toEpochMilliseconds())

        fun profile(syncId: String, name: String, owner: Boolean = false, there: Boolean = false) =
            MatchProfile(syncId, name, "amber", owner, kids = false, historyCount = 18, devices = if (there) listOf(PIXEL) else emptyList())

        val OWNER_HERE = profile("a1", "Arshad", owner = true)
        val MIRA_HERE = profile("m1", "Mira")
        val YURI_HERE = profile("y1", "Yuri")
        val GUESTS = profile("G", "Guests", there = true)
        val PREVIEW = MatchPreview(
            local = listOf(OWNER_HERE, MIRA_HERE, YURI_HERE, profile("k1", "Kai")),
            remote = listOf(profile("A", "Arshad", owner = true, there = true), profile("M", "Mira", there = true), GUESTS),
            pairs = mapOf("a1" to "A", "m1" to "M"),
            fixed = mapOf("a1" to "A"),
        )
    }
}
