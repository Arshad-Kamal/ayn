package com.arshad.ayn.ui.tv.settings

import android.net.Uri
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.settings.backupGateKicker
import com.arshad.ayn.feature.settings.BackupAction
import com.arshad.ayn.ui.tv.profiles.refusalCopy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV's Backup & restore copy against `kp-backup` v1 and the branches the mockups don't draw.
 *  Robolectric only for `android.net.Uri`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCopyTest {

    @Test
    fun firstLaunchsConfirmNamesTheProfilesTheDeviceSettingsAndTheOwner() {
        assertEquals(
            "It brings back 2 profiles (Arshad and Zainab) with everything each keeps, and this Shield’s settings: " +
                "interface size, quality cap, frame-rate matching, clock and Hero on Home. Arshad becomes the owner. Locks " +
                "aren’t in backups, so every profile arrives unlocked.",
            firstRestoreText(listOf(summary("Arshad", owner = true), summary("Zainab", kids = true)), deviceSettings = true),
        )
        assertEquals(
            "It brings back 1 profile (Arshad) with everything it keeps. Arshad becomes the owner. Locks aren’t in " +
                "backups, so it arrives unlocked.",
            firstRestoreText(listOf(summary("Arshad", owner = true)), deviceSettings = false),
        )
    }

    @Test
    fun choosingProfilesAtFirstLaunchSaysWhenTheDeviceSettingsStayAtDefaults() {
        assertEquals("come back only with every profile. With Yuri left out, they start at their defaults.", firstPickDeviceLine(listOf("Yuri")))
        assertEquals("come back too: $DEVICE_SETTINGS.", firstPickDeviceLine(emptyList()))
    }

    @Test
    fun theOwnerGateSaysWhichFlowItOpens() {
        assertEquals("Exporting a backup", backupGateKicker(BackupAction.Export))
        assertEquals("Restoring a backup", backupGateKicker(BackupAction.Read(Uri.EMPTY, null)))
        assertEquals("Only the owner restores backups. Enter Arshad’s PIN.", backupGateLine(BackupAction.Read(Uri.EMPTY, null), "Arshad", LockKind.Pin))
    }

    @Test
    fun onlyNotABackupReadsDifferentlyInSettings() {
        BackupRefusal.entries.filter { it != BackupRefusal.NotABackup }.forEach { reason ->
            assertEquals(refusalCopy(reason), settingsRefusalCopy(reason))
        }
    }

    private fun summary(name: String, owner: Boolean = false, kids: Boolean = false) =
        BackupProfileSummary(name, ProfileTint.Amber, owner, kids, ProfileCounts(6, 41, 0, 0, 0, if (kids) 1 else 0))
}
