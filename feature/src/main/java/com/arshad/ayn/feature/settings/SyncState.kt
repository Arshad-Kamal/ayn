package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.sync.MatchChoices
import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.profiles.UnlockEntry

/**
 * Settings › Sync (`m8-sync-tv`, `m8-sync-phone`; M8-D9, M8-D18): the owner's page. [status] is
 * the runner's; the page reads off, on, syncing or failed from it. [toast] shows once, then the UI
 * consumes it. [drive]: this build can sign in to Google Drive (M8-D1: no client id hides it).
 */
data class SyncUiState(
    val isOwner: Boolean = false,
    val drive: Boolean = false,
    val status: SyncStatus = SyncStatus(linked = false),
    val screen: SyncScreen = SyncScreen.Page,
    val toast: SyncToast? = null,
) {
    val off: Boolean get() = status.isOff
}

/** The page offers Turn on sync: unlinked, or a join left before Match was confirmed (Turn on goes
 *  back to Match). */
val SyncStatus.isOff: Boolean get() = !linked || awaitingMatch

/** What shows over the page. A field's typed text other than a secret is kept here, so a problem
 *  or a step back finds it as it was; passwords and the passphrase stay in the UI's field. */
sealed interface SyncScreen {
    data object Page : SyncScreen

    /** An owner-only [action] asks for the owner's [lockKind] lock first; BACK returns to the page. */
    data class OwnerGate(val action: SyncAction, val lockKind: LockKind, val entry: UnlockEntry = UnlockEntry()) : SyncScreen

    /** "Where should Ayn keep your sync files?" */
    data object Services : SyncScreen

    /** Nextcloud's address; [checking] while Ayn asks it for a sign-in. [again]: Sign in again
     *  after the service refused the sign-in, which keeps the link. */
    data class NextcloudAddress(
        val address: String = "",
        val problem: SetupProblem? = null,
        val checking: Boolean = false,
        val again: Boolean = false,
    ) : SyncScreen

    /** Waiting while the user grants access at [loginUrl] (the phone opens it, the TV shows its
     *  QR); [checking] once granted, while Ayn looks in the folder. */
    data class NextcloudWaiting(
        val loginUrl: String,
        val server: String,
        val address: String,
        val again: Boolean = false,
        val checking: Boolean = false,
    ) : SyncScreen

    /**
     * Google's sign-in (device flow): no [code] while Ayn asks Google for one; [checking] once the
     * user allowed Ayn, while Ayn looks in the Drive (or, for Sign in again ([again]), saves the
     * sign-in). A [problem] ends the wait; Try again asks for a new code.
     */
    data class GoogleSignIn(
        val code: DriveCode? = null,
        val problem: SetupProblem? = null,
        val checking: Boolean = false,
        val again: Boolean = false,
    ) : SyncScreen

    /** Another WebDAV server's address and user name; [checking] while Connect tries them. */
    data class WebDav(
        val address: String = "",
        val user: String = "",
        val problem: SetupProblem? = null,
        val checking: Boolean = false,
        val again: Boolean = false,
    ) : SyncScreen

    /**
     * The sync passphrase for [account]'s folder: [joining] when it holds other devices' files
     * ([devices]) or this device links again after its link was lost, else a new one is chosen.
     * [linking] while Ayn links (and, for a join, reads the profiles to match).
     */
    data class Passphrase(
        val account: SyncAccount,
        val devices: List<SyncDevice>,
        val joining: Boolean,
        val wrong: Boolean = false,
        val failure: SyncFailure? = null,
        val linking: Boolean = false,
    ) : SyncScreen {
        /** No other device's files there, so linking may start the folder with this passphrase:
         *  it needs [MIN_PASSPHRASE] characters, linking again too. */
        val mayStartFolder: Boolean get() = devices.isEmpty()
    }

    /** Match your profiles (M8-D18), its state in [match]; [starting] once Start syncing was pressed. */
    data class Match(val account: SyncAccount?, val match: ProfileMatch, val sheet: MatchSheet? = null, val starting: Boolean = false) : SyncScreen

    /** Reading the profiles to match; once [failure] says why they couldn't be read, Try again or
     *  Cancel (which unlinks, as leaving Match does). */
    data class ReadingMatch(val failure: SyncFailure? = null) : SyncScreen

    /** "Turn off sync on this device?"; [running] once Turn off was pressed. */
    data class TurnOff(val running: Boolean = false) : SyncScreen
}

/** The code to type at [url] (Google's verification page) within [minutes]. */
data class DriveCode(val userCode: String, val url: String, val minutes: Long) {
    /** [url] as the screens name it: `https://www.google.com/device` → "google.com/device". */
    val page: String get() = url.substringAfter("://").removePrefix("www.").trimEnd('/')
}

/** What the owner's lock is asked for. */
enum class SyncAction { TurnOn, SignInAgain, TurnOff }

/** Why a setup step stopped before the passphrase, or a link failed. */
enum class SetupProblem {
    NOT_AN_ADDRESS,
    NEEDS_HTTPS,
    NO_CONNECTION,
    NOT_NEXTCLOUD,

    /** The sign-in's time ran out (Login Flow's 20 minutes, Google's code). */
    EXPIRED,

    /** The user refused Ayn on Google's page. */
    DENIED,

    /** Sign in again reaching another folder than the link's (another account or address). */
    OTHER_ACCOUNT,
    WRONG_SIGN_IN,
    NO_FOLDER,
    STORAGE_FULL,
    SERVER_ERROR,
}

/** A Match row's choice sheet, by the profile's sync id. */
sealed interface MatchSheet {
    data class Here(val syncId: String) : MatchSheet

    data class There(val syncId: String) : MatchSheet
}

/**
 * The owner's Match choices as they stand: [with] pairs each local profile (by sync id) with the
 * remote one it becomes, the fixed pairs ([fixed]: owner ↔ owner, a shared sync id) included;
 * a local profile not in [with] is kept apart. A remote profile no local one pairs with is added
 * here unless [skipped], while there is room ([MAX_PROFILES]).
 */
data class ProfileMatch(val preview: MatchPreview, val with: Map<String, String>, val skipped: Set<String>) {
    private val remoteById: Map<String, MatchProfile> = preview.remote.associateBy { it.syncId }

    /** Local id → remote id that no choice changes. */
    val fixed: Map<String, String> get() = preview.fixed

    fun remote(syncId: String): MatchProfile? = remoteById[syncId]

    /** The remote profiles no local one pairs with, in the folder's order. */
    val remoteOnly: List<MatchProfile> get() = preview.remote.filter { it.syncId !in with.values }

    val added: List<MatchProfile> get() = remoteOnly.filter { it.syncId !in skipped }

    /** Profiles this device will hold. */
    val countHere: Int get() = preview.local.size + added.size

    /** The remote profiles local [syncId] may be With: non-owners of its kind not paired with
     *  another local profile. */
    fun candidates(syncId: String): List<MatchProfile> {
        val mine = preview.local.firstOrNull { it.syncId == syncId } ?: return emptyList()
        if (syncId in fixed) return emptyList()
        val taken = with.filterKeys { it != syncId }.values.toSet()
        return preview.remote.filter { !it.owner && it.kids == mine.kids && it.syncId !in taken && it.syncId !in fixed.values }
    }

    /** Local [syncId] With remote [remoteId] (only one of its [candidates]): the remote profile it
     *  was With before is added here while there is room, else not. */
    fun pairedWith(syncId: String, remoteId: String): ProfileMatch {
        if (candidates(syncId).none { it.syncId == remoteId }) return this
        val freed = with[syncId]
        val paired = copy(with = with + (syncId to remoteId), skipped = skipped - remoteId)
        return if (freed == null || paired.countHere <= MAX_PROFILES) paired else paired.copy(skipped = paired.skipped + freed)
    }

    /** Local [syncId] kept apart: the remote profile it was With is added here while there is
     *  room, else not. */
    fun keptApart(syncId: String): ProfileMatch {
        if (syncId in fixed) return this
        val freed = with[syncId] ?: return this
        val apart = copy(with = with - syncId)
        return if (apart.countHere <= MAX_PROFILES) apart else apart.copy(skipped = skipped + freed)
    }

    /** Remote-only [syncId] added: only while there is room. */
    fun addedHere(syncId: String): ProfileMatch =
        if (syncId in skipped && remoteOnly.any { it.syncId == syncId } && countHere < MAX_PROFILES) copy(skipped = skipped - syncId) else this

    fun notAdded(syncId: String): ProfileMatch =
        if (remoteOnly.any { it.syncId == syncId }) copy(skipped = skipped + syncId) else this

    /** What the runner keeps (M8-D18): every choice made explicit, so no name rule pairs a profile
     *  kept apart. */
    fun choices(): MatchChoices = MatchChoices(
        pairs = with.filterKeys { it !in fixed },
        apart = preview.local.map { it.syncId }.filter { it !in with }.toSet(),
        skipped = remoteOnly.map { it.syncId }.filter { it in skipped }.toSet(),
    )

    companion object {
        /** Ayn's proposal: the runner's pairs, then each remote-only profile added in order while
         *  there is room. */
        fun of(preview: MatchPreview): ProfileMatch {
            val proposed = ProfileMatch(preview, preview.pairs, emptySet())
            val room = (MAX_PROFILES - preview.local.size).coerceAtLeast(0)
            return proposed.copy(skipped = proposed.remoteOnly.drop(room).mapTo(LinkedHashSet()) { it.syncId })
        }
    }
}

sealed interface SyncToast {
    /** Sync turned on: [profiles] here matched with [devices] after a join; none for a new folder. */
    data class TurnedOn(val profiles: Int? = null, val devices: List<String> = emptyList()) : SyncToast

    /** Sync turned off; [filesLeft] when this device's files couldn't be removed from [service]'s
     *  folder. */
    data class TurnedOff(val filesLeft: Boolean, val service: SyncService? = null) : SyncToast
}
