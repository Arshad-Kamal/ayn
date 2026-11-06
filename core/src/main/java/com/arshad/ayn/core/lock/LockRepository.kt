package com.arshad.ayn.core.lock

import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.ProfileEntity
import com.arshad.ayn.core.store.ProfileSession
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProfileLocks] over the lock columns of `profiles` (M5-D1/D15). One mutex serializes every
 * read-modify-write of them, so two quick tries can't both read the same count. A try is
 * counted, with the wait it starts, before its secret is compared and cleared on a match: a
 * process that dies mid-compare keeps the try. [session] names the open profile, for
 * [ownerPassInOwnSession].
 */
class LockRepository(
    database: KeDatabase,
    private val session: ProfileSession,
    private val hasher: LockHasher = LockHasher(),
    private val clock: Clock = Clock.System,
) : ProfileLocks {
    private val dao = database.profileDao()
    private val mutex = Mutex()

    override suspend fun status(profileId: Long): LockStatus = mutex.withLock {
        val profile = dao.profile(profileId)
        val stored = profile?.storedLock() ?: return@withLock LockStatus.None
        LockStatus.Locked(stored.kind, currentWait(profile))
    }

    override suspend fun unlock(profileId: Long, attempt: LockSecret): UnlockResult<LockPass> = mutex.withLock {
        val profile = dao.profile(profileId) ?: return@withLock UnlockResult.Wrong(triesBeforeWait = 0, wait = null)
        open(profile, attempt) { LockPass(profileId) }
    }

    override suspend fun passWithoutLock(profileId: Long): LockPass? = mutex.withLock {
        dao.profile(profileId)?.takeIf { it.storedLock() == null }?.let { LockPass(it.id) }
    }

    override suspend fun setLock(pass: LockPass, secret: LockSecret?): Boolean {
        val stored = secret?.let { hasher.hash(it) }
        return mutex.withLock {
            val refused = if (stored == null) ownerLockHeld(pass.profileId) else dao.isKids(pass.profileId)
            if (!refused) writeLock(pass.profileId, stored)
            !refused
        }
    }

    override suspend fun ownerPassWithoutLock(): OwnerPass? = mutex.withLock {
        if (dao.owner()?.storedLock() == null) OwnerPass() else null
    }

    override suspend fun ownerPassInOwnSession(): OwnerPass? {
        val open = session.currentIdOrNull ?: return null
        return if (dao.owner()?.id == open) OwnerPass() else null
    }

    override suspend fun unlockOwner(attempt: LockSecret): UnlockResult<OwnerPass> = mutex.withLock {
        val owner = dao.owner() ?: return@withLock UnlockResult.Opened(OwnerPass())
        open(owner, attempt) { OwnerPass() }
    }

    override suspend fun unlockOwnerFromKids(kidsProfileId: Long, attempt: LockSecret): UnlockResult<OwnerPass> =
        mutex.withLock {
            val kid = dao.profile(kidsProfileId)?.takeIf { it.kids }
                ?: return@withLock UnlockResult.Wrong(triesBeforeWait = 0, wait = null)
            val stored = dao.owner()?.storedLock() ?: return@withLock UnlockResult.Opened(OwnerPass())
            openCounted(counter = kid, stored, attempt) { OwnerPass() }
        }

    override suspend fun ownerStatusFromKids(kidsProfileId: Long): LockStatus = mutex.withLock {
        val stored = dao.owner()?.storedLock() ?: return@withLock LockStatus.None
        val kid = dao.profile(kidsProfileId)?.takeIf { it.kids }
        LockStatus.Locked(stored.kind, kid?.let { currentWait(it) })
    }

    override suspend fun resetLock(owner: OwnerPass, profileId: Long) {
        mutex.withLock { if (!ownerLockHeld(profileId)) writeLock(profileId, stored = null) }
    }

    /** Whether [profileId] is the owner while a kids profile exists, so its lock stays (KP-D14).
     *  Runs under [mutex]. */
    private suspend fun ownerLockHeld(profileId: Long): Boolean =
        dao.owner()?.id == profileId && dao.kidsExist()

    /** Sets [profileId]'s lock to [stored], or clears it when `null`. Runs under [mutex]. */
    private suspend fun writeLock(profileId: Long, stored: StoredLock?) {
        dao.setLock(
            id = profileId,
            kind = stored?.kind?.key,
            hash = stored?.hash?.let(Base64::encode),
            salt = stored?.salt?.let(Base64::encode),
            iterations = stored?.iterations ?: 0,
        )
    }

    /** Runs under [mutex]. */
    private suspend fun <P> open(profile: ProfileEntity, attempt: LockSecret, pass: () -> P): UnlockResult<P> {
        val stored = profile.storedLock() ?: return UnlockResult.Opened(pass())
        return openCounted(counter = profile, stored, attempt, pass)
    }

    /** Checks [attempt] against [stored], counting the try on [counter]'s tries (the locked
     *  profile's own, or a kids profile's for the owner's lock, KP-D9). Runs under [mutex]. */
    private suspend fun <P> openCounted(
        counter: ProfileEntity,
        stored: StoredLock,
        attempt: LockSecret,
        pass: () -> P,
    ): UnlockResult<P> {
        currentWait(counter)?.let { return UnlockResult.Waiting(it) }
        val tries = counter.failedTries + 1
        val wait = waitStep(tries)
        dao.setTries(counter.id, tries, wait?.let { nowMillis() + it.inWholeMilliseconds })
        if (!hasher.matches(attempt, stored)) return UnlockResult.Wrong(triesBeforeWait(tries), wait)
        dao.setTries(counter.id, failedTries = 0, waitUntilMillis = null)
        return UnlockResult.Opened(pass())
    }

    /** What is left of [profile]'s wait, saving it pulled in or cleared when the clock moved
     *  (see [effectiveWaitUntil]). Runs under [mutex]. */
    private suspend fun currentWait(profile: ProfileEntity): Duration? {
        val now = nowMillis()
        val waitUntil = effectiveWaitUntil(profile.failedTries, profile.waitUntilMillis, now)
        if (waitUntil != profile.waitUntilMillis) dao.setTries(profile.id, profile.failedTries, waitUntil)
        return waitUntil?.let { (it - now).milliseconds }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()
}

private fun ProfileEntity.storedLock(): StoredLock? {
    val hash = lockHash ?: return null
    // ProfileDao.setLock writes the salt with the hash; a row missing it throws in the compare,
    // after the try is counted, so it never opens.
    return StoredLock(LockKind.of(lockKind), Base64.decode(hash), Base64.decode(lockSalt.orEmpty()), lockIterations)
}
