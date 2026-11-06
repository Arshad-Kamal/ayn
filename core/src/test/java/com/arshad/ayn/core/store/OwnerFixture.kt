package com.arshad.ayn.core.store

import kotlinx.coroutines.flow.first

/** The owner as first launch creates it ("Owner", amber), made on the first call and returned
 *  as it is after that. */
internal suspend fun ProfileStore.ensureOwner(): Profile {
    val id = (createOwner(OWNER_NAME, ProfileTint.Amber) as ProfileEdit.Done).profileId
    return profiles().first().single { it.id == id }
}
