package com.arshad.ayn.core.lock

import kotlin.time.Duration

/**
 * Profile locks (§ B3p, M5-D15): unlocking, setting and resetting locks, and the owner gate.
 * Wrong tries and waits are kept per profile across restarts; owner-gate prompts count on the
 * owner's tries, except those raised from a kids profile, which count on its own. Locks are tamper-evident, not tamper-proof (B-D9).
 */
interface ProfileLocks {
    /** [profileId]'s lock and any wait it is in; [LockStatus.None] for an unknown id. */
    suspend fun status(profileId: Long): LockStatus

    /** Opens [profileId] with [attempt]. A profile with no lock opens without one; an unknown id
     *  is refused as a wrong try that counts nowhere. */
    suspend fun unlock(profileId: Long, attempt: LockSecret): UnlockResult<LockPass>

    /** A pass for [profileId] when it has no lock (to set its first one); otherwise `null`. */
    suspend fun passWithoutLock(profileId: Long): LockPass?

    /** Sets the pass's profile's lock to [secret], or removes it when `null`; tries start over.
     *  Changing or removing a lock takes a pass from [unlock] with the current one. `false`, with
     *  nothing changed, for a kids profile (it has no lock of its own) or for removing the
     *  owner's lock while a kids profile exists (KP-D14). */
    suspend fun setLock(pass: LockPass, secret: LockSecret?): Boolean

    /** The owner-only pass without asking: when the device has no owner yet (first launch) or
     *  the owner has no lock. `null` means the owner's lock is needed ([unlockOwner]). */
    suspend fun ownerPassWithoutLock(): OwnerPass?

    /** The owner-only pass for a write the owner makes from their own open profile (a Block
     *  that reaches other profiles, KP-D19): opening it took the owner's lock. `null` unless the
     *  session's profile is the owner's. */
    suspend fun ownerPassInOwnSession(): OwnerPass?

    /** The owner-only pass for the owner's lock, counted on the owner's tries; free when the
     *  device has no owner yet. */
    suspend fun unlockOwner(attempt: LockSecret): UnlockResult<OwnerPass>

    /** The owner-only pass for the owner's lock asked from kids profile [kidsProfileId] (leaving
     *  it, its Parent page, More time): counted on that profile's own tries, so a kid's wrong tries
     *  never make the owner wait (KP-D9). Free when the owner has no lock; refused as a wrong try
     *  counted nowhere when [kidsProfileId] is no kids profile. */
    suspend fun unlockOwnerFromKids(kidsProfileId: Long, attempt: LockSecret): UnlockResult<OwnerPass>

    /** The owner's lock as [unlockOwnerFromKids] asks for it, with the wait [kidsProfileId]'s own
     *  tries are in; [LockStatus.None] when the owner has no lock. */
    suspend fun ownerStatusFromKids(kidsProfileId: Long): LockStatus

    /** Removes [profileId]'s lock and its tries; its user sets a new one. The owner's lock stays
     *  while a kids profile exists (KP-D14). */
    suspend fun resetLock(owner: OwnerPass, profileId: Long)
}

sealed interface LockStatus {
    data object None : LockStatus

    /** [wait] is what is left of a wait after too many wrong tries, `null` when none. */
    data class Locked(val kind: LockKind, val wait: Duration?) : LockStatus
}

sealed interface UnlockResult<out P> {
    data class Opened<P>(val pass: P) : UnlockResult<P>

    /** [triesBeforeWait] wrong tries are left before one starts a wait (0 once they do);
     *  [wait] is the wait this one started. */
    data class Wrong(val triesBeforeWait: Int, val wait: Duration?) : UnlockResult<Nothing>

    /** Refused unchecked and uncounted: a wait has [remaining] left. */
    data class Waiting(val remaining: Duration) : UnlockResult<Nothing>
}

/** Proof that [profileId] was just opened with its lock, or has none. Ask for it when the flow
 *  that needs it starts and drop it when that flow ends (M5-D15). */
class LockPass internal constructor(val profileId: Long)

/** Proof that the owner gate was just passed, which every owner-only write takes. Ask for it
 *  when the flow that needs it starts and drop it when that flow ends (M5-D15). */
class OwnerPass internal constructor()
