package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.lock.LockStatus
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The owner's lock asked from a kids profile and counted on that profile's own tries (KP-D23):
 * the rail's Leave and Time's up's asks. [screen] is the gate's screen now. Main thread, like
 * its host ViewModel.
 */
internal class OwnerFromKids(
    private val scope: CoroutineScope,
    private val locks: ProfileLocks,
    private val entry: LockEntryInput,
    private val screen: () -> GateScreen,
) {
    /** [owner]'s lock asked from [kid]: a correct entry hands its pass to [opened]. Once the
     *  status is read, a wait it is in counts down, or an owner whose lock went away passes free;
     *  both only while the screen is still [asking]. */
    fun ask(kid: Profile, owner: Profile, asking: (GateScreen) -> Boolean, opened: suspend (OwnerPass) -> Unit) {
        owner.lock?.let { kind -> entry.startTry(kind, check = { locks.unlockOwnerFromKids(kid.id, it) }, opened = opened) }
        scope.launch {
            val status = locks.ownerStatusFromKids(kid.id)
            if (!asking(screen())) return@launch
            when (status) {
                LockStatus.None -> locks.ownerPassWithoutLock()?.let { opened(it) }
                is LockStatus.Locked -> status.wait?.let(entry::waitFor)
            }
        }
    }
}
