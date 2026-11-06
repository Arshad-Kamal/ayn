package com.arshad.ayn.ui.tv.kids

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker

/** The Parent page's lock (`kp-kids` parentgate): [owner]'s, BACK back to [kid]'s Home. */
internal fun parentGateCopy(kid: String, owner: String, kind: LockKind): EntryCopy = EntryCopy(
    kicker = EntryKicker(R.drawable.ic_lock, "Parent"),
    line = when (kind) {
        LockKind.Sequence -> "The Parent page needs $owner’s lock. Enter $owner’s sequence, then press OK."
        LockKind.Pin -> "The Parent page needs $owner’s lock. Enter $owner’s PIN."
    },
    hint = "BACK returns to $kid’s Home",
    waitTail = ", or press BACK to return to $kid’s Home.",
)
