package com.arshad.ayn.core.lock

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [ProfileLocks] for the layers above the store, on § B3p's try rules ([waitStep],
 * [triesBeforeWait]) without a clock: a wait lasts until the test clears [waits]. As in the real
 * store, a try is counted (and its wait started) before the compare: it is recorded in [tries]
 * and, when wrong, counted before [unlockGate] (when set) holds it, so a test can see a try in
 * flight. A profile is unknown unless [known] or [locks] has it (no profile store behind this).
 * [ownerId] names the owner for the owner gate; `null` means the device has none yet. [kids]
 * holds the kids profiles, for KP-D14's refusals. [sessionId] is the open profile.
 */
class FakeProfileLocks : ProfileLocks {
    val locks: MutableMap<Long, LockSecret> = mutableMapOf()
    val known: MutableSet<Long> = mutableSetOf()
    val wrongTries: MutableMap<Long, Int> = mutableMapOf()
    val waits: MutableMap<Long, Duration> = mutableMapOf()
    val tries: MutableList<Long> = mutableListOf()
    var unlockGate: CompletableDeferred<Unit>? = null

    /** When set, [setLock] waits for it before writing, so a test can hold a save. */
    var setLockGate: CompletableDeferred<Unit>? = null

    /** When set, [status] waits for it, so a test can hold the read. */
    var statusGate: CompletableDeferred<Unit>? = null
    var ownerId: Long? = null
    val kids: MutableSet<Long> = mutableSetOf()
    var sessionId: Long? = null

    override suspend fun status(profileId: Long): LockStatus {
        statusGate?.await()
        return locks[profileId]?.let { LockStatus.Locked(it.kind, waits[profileId]) } ?: LockStatus.None
    }

    override suspend fun unlock(profileId: Long, attempt: LockSecret): UnlockResult<LockPass> {
        val secret = locks[profileId]
        if (secret == null) {
            return if (profileId in known) UnlockResult.Opened(LockPass(profileId)) else UnlockResult.Wrong(0, null)
        }
        waits[profileId]?.let { return UnlockResult.Waiting(it) }
        tries += profileId
        val failed = (wrongTries[profileId] ?: 0) + 1
        val wait = waitStep(failed)
        wrongTries[profileId] = failed
        if (wait != null) waits[profileId] = wait
        unlockGate?.await()
        if (secret.material().contentEquals(attempt.material())) {
            clearTries(profileId)
            return UnlockResult.Opened(LockPass(profileId))
        }
        return UnlockResult.Wrong(triesBeforeWait(failed), wait)
    }

    override suspend fun passWithoutLock(profileId: Long): LockPass? =
        if (locks[profileId] == null) LockPass(profileId) else null

    override suspend fun setLock(pass: LockPass, secret: LockSecret?): Boolean {
        val refused = if (secret == null) ownerLockHeld(pass.profileId) else pass.profileId in kids
        if (refused) return false
        setLockGate?.await()
        if (secret == null) locks.remove(pass.profileId) else locks[pass.profileId] = secret
        clearTries(pass.profileId)
        return true
    }

    override suspend fun ownerPassWithoutLock(): OwnerPass? {
        val owner = ownerId ?: return OwnerPass()
        return if (locks[owner] == null) OwnerPass() else null
    }

    override suspend fun ownerPassInOwnSession(): OwnerPass? =
        if (sessionId != null && sessionId == ownerId) OwnerPass() else null

    override suspend fun unlockOwner(attempt: LockSecret): UnlockResult<OwnerPass> {
        val owner = ownerId ?: return UnlockResult.Opened(OwnerPass())
        return when (val result = unlock(owner, attempt)) {
            is UnlockResult.Opened -> UnlockResult.Opened(OwnerPass())
            is UnlockResult.Wrong -> result
            is UnlockResult.Waiting -> result
        }
    }

    /** Counted on [kidsProfileId]'s entries in [wrongTries] and [waits], as the real store does. */
    override suspend fun unlockOwnerFromKids(kidsProfileId: Long, attempt: LockSecret): UnlockResult<OwnerPass> {
        if (kidsProfileId !in kids) return UnlockResult.Wrong(0, null)
        val secret = ownerId?.let { locks[it] } ?: return UnlockResult.Opened(OwnerPass())
        waits[kidsProfileId]?.let { return UnlockResult.Waiting(it) }
        tries += kidsProfileId
        val failed = (wrongTries[kidsProfileId] ?: 0) + 1
        val wait = waitStep(failed)
        wrongTries[kidsProfileId] = failed
        if (wait != null) waits[kidsProfileId] = wait
        unlockGate?.await()
        if (secret.material().contentEquals(attempt.material())) {
            clearTries(kidsProfileId)
            return UnlockResult.Opened(OwnerPass())
        }
        return UnlockResult.Wrong(triesBeforeWait(failed), wait)
    }

    override suspend fun ownerStatusFromKids(kidsProfileId: Long): LockStatus {
        statusGate?.await()
        val secret = ownerId?.let { locks[it] } ?: return LockStatus.None
        return LockStatus.Locked(secret.kind, waits[kidsProfileId]?.takeIf { kidsProfileId in kids })
    }

    override suspend fun resetLock(owner: OwnerPass, profileId: Long) {
        if (ownerLockHeld(profileId)) return
        locks.remove(profileId)
        clearTries(profileId)
    }

    private fun ownerLockHeld(profileId: Long): Boolean = profileId == ownerId && kids.isNotEmpty()

    private fun clearTries(profileId: Long) {
        wrongTries.remove(profileId)
        waits.remove(profileId)
    }
}
