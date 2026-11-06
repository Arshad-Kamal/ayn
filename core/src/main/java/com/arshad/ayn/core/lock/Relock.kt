package com.arshad.ayn.core.lock

import com.arshad.ayn.core.store.Profile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the root gate does with the current session (§ B3p). Ordered by strength: a stronger
 *  decision replaces a weaker one still waiting to be handled. */
enum class RelockDecision {
    Stay,

    /** The picker, over an unlocked profile; picking it again resumes. */
    Picker,

    /** The current profile is locked again: the TV stops and clears the player for the picker; a
     *  phone keeps a held video under that profile's unlock (M6-D8). */
    Relock,
}

/** Why a locked profile re-locked: the screen went off, or it was away or idle too long. */
enum class RelockReason { ScreenOff, Away }

/** The current profile as the re-lock rules see it; [pinnedKids]: a kids profile, which Ayn is
 *  pinned to while it is open (KP-D9). */
data class SessionLock(val locked: Boolean, val profileCount: Int, val pinnedKids: Boolean = false)

/** [currentId]'s [SessionLock] among [profiles]; `null` while either is unknown or the current
 *  profile isn't among them. */
fun sessionLockOf(profiles: List<Profile>?, currentId: Long?): SessionLock? {
    val current = profiles?.firstOrNull { it.id == currentId } ?: return null
    return SessionLock(locked = current.lock != null, profileCount = profiles.size, pinnedKids = current.isKids)
}

/**
 * The signals the rules read, on a monotonic clock. [lastActiveAt] is the last key press, leave
 * on purpose, playback in the foreground, or activity start (the screensaver stops the activity,
 * so its time counts as background, M5-D9).
 */
data class RelockSignals(
    val now: Duration,
    val lastActiveAt: Duration,
    val playingInForeground: Boolean,
    val screenWentOff: Boolean,
)

/**
 * § B3p re-lock rules: a locked profile re-locks after the TV sleeps or after
 * [LOCKED_PROFILE_IDLE] in the background or idle in the foreground (playback in the
 * foreground exempt); with two or more profiles an unlocked one gets the picker back after
 * [UNLOCKED_PROFILE_AWAY]. A pinned kids profile stays: only the owner's lock leaves it (KP-D9).
 */
fun relockDecision(lock: SessionLock, signals: RelockSignals): RelockDecision {
    val inactive = if (signals.playingInForeground) Duration.ZERO else signals.now - signals.lastActiveAt
    return when {
        lock.pinnedKids -> RelockDecision.Stay
        lock.locked && (signals.screenWentOff || inactive >= LOCKED_PROFILE_IDLE) -> RelockDecision.Relock
        !lock.locked && lock.profileCount >= 2 && inactive >= UNLOCKED_PROFILE_AWAY -> RelockDecision.Picker
        else -> RelockDecision.Stay
    }
}

/** Why [relockDecision] answered [RelockDecision.Relock] on [signals]: its screen-off clause, else
 *  the idle one. */
fun relockReason(signals: RelockSignals): RelockReason =
    if (signals.screenWentOff) RelockReason.ScreenOff else RelockReason.Away

/** Where a cold start goes (§ B3p). */
sealed interface LaunchGate {
    /** No profile yet: restore a backup or name the owner. */
    data object FirstLaunch : LaunchGate

    data object Picker : LaunchGate

    /** The device's one profile, unlocked: straight to Home. */
    data class Open(val profileId: Long) : LaunchGate
}

/** The kids profile [pinnedId] names among [profiles], which a start opens before anything else
 *  (KP-D9); `null` when nothing is pinned or no kids profile has that id any more (removed, or
 *  replaced by a restore). */
fun pinnedProfile(profiles: List<Profile>, pinnedId: Long?): Profile? =
    profiles.firstOrNull { it.id == pinnedId && it.isKids }

fun launchGate(profiles: List<Profile>): LaunchGate {
    val only = profiles.singleOrNull()
    return when {
        profiles.isEmpty() -> LaunchGate.FirstLaunch
        only != null && only.lock == null -> LaunchGate.Open(only.id)
        else -> LaunchGate.Picker
    }
}

/**
 * After KE's process died while it was away (a restart from saved state, M5-D21): the profile
 * [away] left open, when the re-lock rules on the time since [AwayRecord.lastActiveAt] let it reopen
 * without the gate; `null` sends the restart through [launchGate]. A screen-off while the process
 * was dead reaches nothing, so a locked profile always takes the gate. A last active time later
 * than [now] is from an earlier boot (the clock restarts at boot).
 */
fun reopenAfterRestart(profiles: List<Profile>, away: AwayRecord?, now: Duration): Profile? {
    val record = away ?: return null
    val profile = profiles.firstOrNull { it.id == record.profileId } ?: return null
    if (profile.lock != null || record.lastActiveAt > now) return null
    val signals = RelockSignals(now, record.lastActiveAt, playingInForeground = false, screenWentOff = false)
    val lock = SessionLock(locked = profile.lock != null, profiles.size, pinnedKids = profile.isKids)
    return profile.takeIf { relockDecision(lock, signals) == RelockDecision.Stay }
}

val LOCKED_PROFILE_IDLE: Duration = 5.minutes
val UNLOCKED_PROFILE_AWAY: Duration = 30.minutes

/** How often the foreground checks for idleness; a re-lock can land this much late. */
val RELOCK_CHECK_INTERVAL: Duration = 15.seconds
