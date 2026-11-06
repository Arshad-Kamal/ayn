package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.LockStatus
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.ProfileLocks
import kotlin.time.Duration

/** How the owner gate opens for an owner-only write (M5-D15, § B3p). */
sealed interface OwnerGateOpening {
    /** The owner has no lock: the pass comes without asking. */
    data class Free(val pass: OwnerPass) : OwnerGateOpening

    /** Ask for the owner's [kind] lock (counted on the owner's tries); [wait] is a wait it is
     *  already in. */
    data class Ask(val kind: LockKind, val wait: Duration?) : OwnerGateOpening

    /** The owner's lock went away between the two reads: the next try takes the free pass. */
    data object Changed : OwnerGateOpening
}

/** Opens the owner gate for [ownerId], the owner's profile. */
suspend fun ProfileLocks.openOwnerGate(ownerId: Long): OwnerGateOpening {
    ownerPassWithoutLock()?.let { return OwnerGateOpening.Free(it) }
    return when (val status = status(ownerId)) {
        LockStatus.None -> OwnerGateOpening.Changed
        is LockStatus.Locked -> OwnerGateOpening.Ask(status.kind, status.wait)
    }
}

/** Sets [secret] as the first lock of [ownerId], the owner's profile; `true` too when it has
 *  one already (no pass without a lock), which is all KP-D14 asks. */
suspend fun ProfileLocks.setOwnerLockIfNone(ownerId: Long, secret: LockSecret): Boolean {
    val pass = passWithoutLock(ownerId) ?: return true
    return setLock(pass, secret)
}
