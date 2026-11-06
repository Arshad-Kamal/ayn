package com.arshad.ayn.core.store

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.flow.Flow

/**
 * Profiles seam (B-D9, `06-tv-ui-rebuild.md` § B3p): who uses KE on this device. Feature/UI code
 * programs against this interface, never against Room types (ARCH-4).
 */
interface ProfileStore {
    /** Every profile, the owner first, then in the order they were added. */
    fun profiles(): Flow<List<Profile>>

    /** Each profile's counts by profile id, re-emitted when they change. A profile missing from
     *  the map (just created or restored, before the counts catch up) has nothing counted yet. */
    fun contents(): Flow<Map<Long, ProfileContents>>

    /** Creates the owner at first launch (M5-D16). When the device already has an owner (a
     *  second press, a restore in between) it is left as it is and its id comes back. */
    suspend fun createOwner(name: String, tint: ProfileTint): ProfileEdit

    /** Adds a non-owner profile; a [kids] one only while the owner has a lock (KP-D14), and with
     *  Harmful for kids on as the owner's restriction (FPK-D1). */
    suspend fun create(owner: OwnerPass, name: String, tint: ProfileTint, kids: Boolean = false): ProfileEdit

    suspend fun rename(profileId: Long, name: String): ProfileEdit

    suspend fun setTint(profileId: Long, tint: ProfileTint)

    /** Removes a non-owner profile with everything it holds; `false` for the owner or an
     *  unknown id. */
    suspend fun remove(owner: OwnerPass, profileId: Long): Boolean
}

data class Profile(
    val id: Long,
    val name: String,
    val tint: ProfileTint,
    val isOwner: Boolean,
    /** `null` when the profile has no lock. */
    val lock: LockKind?,
    /** Shows only what the owner approved, within its time rules (KP-D3, D8). */
    val isKids: Boolean = false,
)

/** A profile's subscriptions, its own playlists (Watch later not counted) and its history
 *  entries (every video it has opened, watched to the end or not). */
data class ProfileContents(val subscriptionCount: Int, val playlistCount: Int, val historyCount: Int) {
    companion object {
        /** Nothing counted: what a profile missing from [ProfileStore.contents] holds. */
        val Empty: ProfileContents = ProfileContents(subscriptionCount = 0, playlistCount = 0, historyCount = 0)
    }
}

/** The Cinema profile tints (`--p-*` in the master sandbox), in the order new profiles take them. */
enum class ProfileTint(val key: String) {
    Amber("amber"),
    Rose("rose"),
    Teal("teal"),
    Blue("blue"),
    Violet("violet"),
    ;

    companion object {
        /** The tint stored as [key]; amber for a key this build doesn't know. */
        fun of(key: String): ProfileTint = entries.firstOrNull { it.key == key } ?: Amber
    }
}

/** How a create or rename went. */
sealed interface ProfileEdit {
    data class Done(val profileId: Long) : ProfileEdit

    /** Blank after trimming, or longer than [MAX_PROFILE_NAME] characters. */
    data object NameInvalid : ProfileEdit

    /** Another profile has the name, ignoring case. */
    data object NameTaken : ProfileEdit

    /** The device already has [MAX_PROFILES] profiles. */
    data object Full : ProfileEdit

    /** A kids profile needs the owner to have a lock first (KP-D14). */
    data object OwnerLockNeeded : ProfileEdit
}

const val MAX_PROFILES = 5
const val MAX_PROFILE_NAME = 20

/** The owner's name when KE creates it on the person's behalf (the v1→v2 upgrade; the
 *  container until the gate is wired, M5.6b); renamable in Profiles. */
const val OWNER_NAME = "Owner"
