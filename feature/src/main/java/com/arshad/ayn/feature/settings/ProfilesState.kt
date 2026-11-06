package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.UnlockEntry
import java.time.DayOfWeek

/**
 * Settings › Profiles (`b3-profiles`, § B3p). [me] is the open profile; [rows] are every profile
 * for the owner, only [me] for anyone else, whose page names the owner ([ownerName]). [toast]
 * shows once, then the UI consumes it.
 */
data class ProfilesUiState(
    val me: Profile? = null,
    val rows: List<ProfileRow> = emptyList(),
    val ownerName: String? = null,
    val screen: ProfilesScreen = ProfilesScreen.Page,
    val toast: ProfilesToast? = null,
) {
    /** The owner's page warns while the owner has no lock (§ B3p). */
    val ownerHasNoLock: Boolean get() = me?.isOwner == true && me.lock == null

    /** The owner can add a profile while there are fewer than [MAX_PROFILES]. */
    val canAdd: Boolean get() = me?.isOwner == true && rows.size < MAX_PROFILES

    /** The kids profiles' names, which keep the owner's lock from being removed (KP-D14). */
    val kidsNames: List<String> get() = rows.filter { it.profile.isKids }.map { it.profile.name }
}

/** A profile's row; [kid] is what a kids profile is allowed (its approved channels are its
 *  subscriptions, counted in [contents]). */
data class ProfileRow(val profile: Profile, val contents: ProfileContents, val kid: KidSummary? = null)

/** A kids profile's approved videos and daily limits (a day missing has none). */
data class KidSummary(val approvedVideos: Int, val dailyLimitMinutes: Map<DayOfWeek, Int>)

/** What shows over the Profiles page. An [EntryScreen] takes lock input. */
sealed interface ProfilesScreen {
    sealed interface EntryScreen : ProfilesScreen {
        val entry: UnlockEntry
    }

    data object Page : ProfilesScreen

    /** Add a profile: a standard or a kids profile? (`kp-kids` kind). Kids hands over to
     *  [KidsAddViewModel], with this sheet waiting under it. */
    data object ProfileKind : ProfilesScreen

    /** The owner's own row ("You"): name, colour, lock. A non-owner's page has these rows itself. */
    data object OwnSheet : ProfilesScreen

    /** The owner's sheet for another profile: reset its lock, remove it. */
    data class ProfileSheet(val profileId: Long) : ProfilesScreen

    /** Remove [profileId]? The confirm names its counts. */
    data class ConfirmRemove(val profileId: Long) : ProfilesScreen

    /** An owner-only [action] asks for the owner's [lockKind] lock first. */
    data class OwnerGate(
        val action: OwnerAction,
        val lockKind: LockKind,
        override val entry: UnlockEntry = UnlockEntry(),
    ) : EntryScreen

    /** A new profile's name and colour, after the owner gate. */
    data class AddForm(
        val name: String = "",
        val tint: ProfileTint,
        val problem: NameProblem? = null,
        val saving: Boolean = false,
    ) : ProfilesScreen

    /** Your name, edited in its row. */
    data class Rename(val name: String, val problem: NameProblem? = null, val saving: Boolean = false) : ProfilesScreen

    /** Your colour: the tint sheet. */
    data object Colour : ProfilesScreen

    /** Choose a lock kind for your profile (a first lock, or after the current one). */
    data object ChooseKind : ProfilesScreen

    /** Your lock: change or remove it. */
    data object LockMenu : ProfilesScreen

    /** Changing or removing your lock asks for the current one first. */
    data class CurrentLock(
        val change: LockChange,
        val lockKind: LockKind,
        override val entry: UnlockEntry = UnlockEntry(),
    ) : EntryScreen

    /** A new lock, entered twice ([UnlockEntry.confirming]). */
    data class NewLock(val lockKind: LockKind, override val entry: UnlockEntry = UnlockEntry()) : EntryScreen
}

/** The owner-only writes on this page (M5-D15). */
sealed interface OwnerAction {
    data object Add : OwnerAction

    data class Remove(val profileId: Long) : OwnerAction

    data class ResetLock(val profileId: Long) : OwnerAction
}

enum class LockChange { Change, Remove }

enum class NameProblem {
    /** Blank once trimmed. */
    Missing,

    /** Another profile has the name, ignoring case. */
    Taken,

    /** The device already has its 5 profiles. */
    Full,
}

enum class ProfilesToast { LockSaved, LockRemoved }
