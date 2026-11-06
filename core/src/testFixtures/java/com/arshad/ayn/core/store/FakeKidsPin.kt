package com.arshad.ayn.core.store

import com.arshad.ayn.core.lock.OwnerPass

/** In-memory [KidsPin]: [pinnedId] is the record; while [writes] is `false` a pin isn't written
 *  (the real store's failed `commit()`). [unpins] counts the owner's clears. */
class FakeKidsPin : KidsPin {
    var pinnedId: Long? = null
    var writes = true
    var unpins = 0

    override suspend fun pinned(): Long? = pinnedId

    override suspend fun pin(profileId: Long): Boolean {
        if (writes) pinnedId = profileId
        return writes
    }

    override suspend fun unpin(owner: OwnerPass) {
        pinnedId = null
        unpins++
    }
}
