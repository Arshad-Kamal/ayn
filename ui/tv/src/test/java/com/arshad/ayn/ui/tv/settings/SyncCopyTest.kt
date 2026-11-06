package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.feature.settings.LaunchNotice
import org.junit.Assert.assertEquals
import org.junit.Test

/** The TV's launch toast (`m8-sync-tv` v2–v3: notice, noticemira, noticeoff) and the failures the
 *  mockups don't draw. */
class SyncCopyTest {

    @Test
    fun theOwnerReadsWhatFailedAndWhereToFixIt() {
        assertEquals(
            "Sync didn’t work:" to "Nextcloud says Ayn’s access was removed. Fix it in Settings › Sync",
            launchNoticeText(LaunchNotice(SyncFailure.SIGN_IN_REJECTED, NEXTCLOUD, isOwner = true, ownerName = "Arshad")),
        )
        assertEquals(
            "Sync didn’t work:" to "cloud.example.org is full. Fix it in Settings › Sync",
            launchNoticeText(LaunchNotice(SyncFailure.STORAGE_FULL, NEXTCLOUD, isOwner = true, ownerName = "Arshad")),
        )
    }

    @Test
    fun anotherProfileIsToldWhomToTell() {
        assertEquals(
            "Sync isn’t working:" to "inform Arshad, who can fix it in Settings › Sync",
            launchNoticeText(LaunchNotice(SyncFailure.SIGN_IN_REJECTED, NEXTCLOUD, isOwner = false, ownerName = "Arshad")),
        )
    }

    @Test
    fun aFailureAynRetriesSaysSoToEveryone() {
        assertEquals(
            "Couldn’t sync:" to "no connection. Ayn tries again by itself",
            launchNoticeText(LaunchNotice(SyncFailure.NO_CONNECTION, NEXTCLOUD, isOwner = false, ownerName = "Arshad")),
        )
        assertEquals(
            "Couldn’t sync:" to "cloud.example.org answered with an error. Ayn tries again by itself",
            launchNoticeText(LaunchNotice(SyncFailure.SERVER_ERROR, NEXTCLOUD, isOwner = true, ownerName = "Arshad")),
        )
        assertEquals(
            "Couldn’t sync:" to "a save failed. Ayn tries again by itself",
            launchNoticeText(LaunchNotice(SyncFailure.NOT_SAVED, NEXTCLOUD, isOwner = true, ownerName = "Arshad")),
        )
    }

    private companion object {
        val NEXTCLOUD = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad")
    }
}
