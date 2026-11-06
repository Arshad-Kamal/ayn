package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.settings.OwnerAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Profiles copy against `b3-profiles` v8 (Q7) and the branches the mockups don't draw. */
class ProfilesCopyTest {

    @Test
    fun aProfileWithOnlyHistoryShowsJustThat() {
        assertEquals("No lock · 18 in history", profileHint(null, ProfileContents(0, 0, 18)))
        assertEquals("Locked with a PIN · 1 subscription · 2 playlists · 96 in history", profileHint(LockKind.Pin, ProfileContents(1, 2, 96)))
    }

    @Test
    fun theOwnerGateNamesItsActionAndTheOwnersLock() {
        assertEquals("Resetting Mira’s lock", ownerGateKicker(OwnerAction.ResetLock(2)) { "Mira" })
        assertEquals("Removing a profile", ownerGateKicker(OwnerAction.Remove(2)) { null })
        assertEquals("Only the owner removes profiles. Enter Arshad’s PIN.", ownerGateLine(OwnerAction.Remove(2), "Arshad", LockKind.Pin))
    }

    @Test
    fun aNewPinIsAskedForTwice() {
        assertEquals("Choose 4 digits.", newLockLine(LockKind.Pin, confirming = false))
        assertEquals("Enter the same PIN again.", newLockLine(LockKind.Pin, confirming = true))
    }
}
