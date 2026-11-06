package com.arshad.ayn.core.lock

import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The § B3p re-lock and cold-start rules as pure functions. */
class RelockTest {

    @Test
    fun aLockedProfileRelocksAfterFiveMinutesInactive() {
        assertEquals(RelockDecision.Stay, relockDecision(LOCKED, inactiveFor(5.minutes - 1.milliseconds)))
        assertEquals(RelockDecision.Relock, relockDecision(LOCKED, inactiveFor(5.minutes)))
    }

    @Test
    fun playbackInTheForegroundKeepsALockedProfileOpen() {
        assertEquals(RelockDecision.Stay, relockDecision(LOCKED, inactiveFor(2.hours, playingInForeground = true)))
    }

    @Test
    fun theScreenGoingOffRelocksALockedProfileAtOnce() {
        assertEquals(RelockDecision.Relock, relockDecision(LOCKED, inactiveFor(Duration.ZERO, screenWentOff = true)))
        assertEquals(RelockDecision.Relock, relockDecision(LOCKED, inactiveFor(Duration.ZERO, playingInForeground = true, screenWentOff = true)))
    }

    @Test
    fun theScreenGoingOffLeavesAnUnlockedProfile() {
        assertEquals(RelockDecision.Stay, relockDecision(UNLOCKED_OF_TWO, inactiveFor(Duration.ZERO, screenWentOff = true)))
    }

    @Test
    fun anUnlockedProfileAmongTwoGetsThePickerAfter30Minutes() {
        assertEquals(RelockDecision.Stay, relockDecision(UNLOCKED_OF_TWO, inactiveFor(30.minutes - 1.milliseconds)))
        assertEquals(RelockDecision.Picker, relockDecision(UNLOCKED_OF_TWO, inactiveFor(30.minutes)))
    }

    @Test
    fun theOnlyProfileUnlockedNeverGetsThePicker() {
        assertEquals(RelockDecision.Stay, relockDecision(SessionLock(locked = false, profileCount = 1), inactiveFor(10.hours)))
    }

    @Test
    fun aLockedProfileAmongTwoRelocksRatherThanGetsThePicker() {
        assertEquals(RelockDecision.Relock, relockDecision(SessionLock(locked = true, profileCount = 2), inactiveFor(30.minutes)))
    }

    @Test
    fun theSessionLockIsTheCurrentProfilesLockAmongAllProfiles() {
        val profiles = listOf(profile(7, lock = null), profile(8, lock = LockKind.Sequence))

        assertEquals(SessionLock(locked = true, profileCount = 2), sessionLockOf(profiles, currentId = 8))
        assertEquals(SessionLock(locked = false, profileCount = 2), sessionLockOf(profiles, currentId = 7))
    }

    @Test
    fun theSessionLockIsUnknownUntilTheProfilesAndTheCurrentOneAre() {
        val profiles = listOf(profile(7, lock = LockKind.Pin))

        assertEquals(null, sessionLockOf(profiles = null, currentId = 7))
        assertEquals(null, sessionLockOf(profiles, currentId = null))
        assertEquals(null, sessionLockOf(profiles, currentId = 9))
    }

    @Test
    fun aColdStartWithNoProfileIsTheFirstLaunch() {
        assertEquals(LaunchGate.FirstLaunch, launchGate(emptyList()))
    }

    @Test
    fun aColdStartWithOneUnlockedProfileOpensIt() {
        assertEquals(LaunchGate.Open(7), launchGate(listOf(profile(7, lock = null))))
    }

    @Test
    fun aColdStartWithOneLockedProfileShowsThePicker() {
        assertEquals(LaunchGate.Picker, launchGate(listOf(profile(7, lock = LockKind.Pin))))
    }

    @Test
    fun aColdStartWithTwoProfilesShowsThePicker() {
        assertEquals(LaunchGate.Picker, launchGate(listOf(profile(7, lock = null), profile(8, lock = null))))
    }

    @Test
    fun aRestartWithinThirtyMinutesReopensTheUnlockedProfileLeftOpen() {
        val unlocked = listOf(profile(7, lock = null), profile(8, lock = null))

        assertEquals(unlocked[1], reopenAfterRestart(unlocked, AwayRecord(8, START), now = START + 30.minutes - 1.milliseconds))
        assertEquals(null, reopenAfterRestart(unlocked, AwayRecord(8, START), now = START + 30.minutes))
    }

    @Test
    fun aRestartLeftOnALockedProfileTakesTheGate() {
        val profiles = listOf(profile(7, lock = LockKind.Sequence), profile(8, lock = null))

        assertEquals(null, reopenAfterRestart(profiles, AwayRecord(7, START), now = START + 1.milliseconds))
    }

    @Test
    fun aRestartWithNoUsableRecordTakesTheGate() {
        val profiles = listOf(profile(7, lock = null), profile(8, lock = null))

        assertEquals(null, reopenAfterRestart(profiles, away = null, now = START))
        assertEquals("the profile is gone", null, reopenAfterRestart(profiles, AwayRecord(9, START), now = START))
        assertEquals("a time from another boot", null, reopenAfterRestart(profiles, AwayRecord(8, START), now = START - 1.milliseconds))
        assertEquals(profiles[1], reopenAfterRestart(profiles, AwayRecord(8, START), now = START))
    }

    /** KP-D9: away for hours, or the screen off, a pinned kids profile stays open. */
    @Test
    fun aPinnedKidsProfileNeverGetsThePickerOrRelocks() {
        val kids = SessionLock(locked = false, profileCount = 2, pinnedKids = true)

        assertEquals(RelockDecision.Stay, relockDecision(kids, inactiveFor(10.hours)))
        assertEquals(RelockDecision.Stay, relockDecision(kids, inactiveFor(Duration.ZERO, screenWentOff = true)))
    }

    @Test
    fun theSessionLockSaysWhenTheCurrentProfileIsAKidsProfile() {
        val profiles = listOf(profile(7, lock = LockKind.Pin), kids(8))

        assertEquals(SessionLock(locked = false, profileCount = 2, pinnedKids = true), sessionLockOf(profiles, currentId = 8))
    }

    @Test
    fun thePinnedProfileIsTheKidsProfileItNames() {
        val profiles = listOf(profile(7, lock = LockKind.Pin), profile(8, lock = null), kids(9))

        assertEquals(profiles[2], pinnedProfile(profiles, pinnedId = 9))
        assertEquals("nothing pinned", null, pinnedProfile(profiles, pinnedId = null))
        assertEquals("no kids profile has the id", null, pinnedProfile(profiles, pinnedId = 8))
        assertEquals("the profile is gone", null, pinnedProfile(profiles, pinnedId = 10))
    }

    /** A kids profile left open reopens however long Ayn was away (its pin reopens it first). */
    @Test
    fun aRestartLeftOnAKidsProfileReopensIt() {
        val profiles = listOf(profile(7, lock = LockKind.Pin), kids(8))

        assertEquals(profiles[1], reopenAfterRestart(profiles, AwayRecord(8, START), now = START + 10.hours))
    }

    private fun kids(id: Long): Profile = Profile(id, "K$id", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)

    private fun inactiveFor(
        inactive: Duration,
        playingInForeground: Boolean = false,
        screenWentOff: Boolean = false,
    ): RelockSignals = RelockSignals(now = START + inactive, lastActiveAt = START, playingInForeground, screenWentOff)

    private fun profile(id: Long, lock: LockKind?): Profile = Profile(id, "P$id", ProfileTint.Amber, isOwner = id == 7L, lock)
}

private val LOCKED = SessionLock(locked = true, profileCount = 1)
private val UNLOCKED_OF_TWO = SessionLock(locked = false, profileCount = 2)
private val START = 1.hours
