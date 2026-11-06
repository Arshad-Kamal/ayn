package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.RelockReason
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import kotlin.time.Duration

/** What the root gate shows (`b3-profiles`, § B3p); [GateScreen.Open] is a profile's shell. */
sealed interface GateScreen {
    /** The profiles haven't loaded: nothing draws yet. */
    data object Loading : GateScreen

    /** First launch: create the owner or restore a backup. */
    data object Welcome : GateScreen

    data class NameOwner(
        val name: String = "",
        val tint: ProfileTint = ProfileTint.Amber,
        /** Continue was pressed with no name. */
        val nameMissing: Boolean = false,
        val saving: Boolean = false,
    ) : GateScreen

    /** A file picked to restore at first launch; [fileName] as the picker named it, if it did. */
    data class Restore(val fileName: String?, val step: RestoreStep) : GateScreen

    /** "Who's watching?": the tiles start on [focusId]. [canReturn]: opened from the rail over an
     *  unlocked profile, so BACK returns to it rather than leaving KE. */
    data class Picker(val focusId: Long?, val canReturn: Boolean = false) : GateScreen

    /** [profile]'s lock opens it; with [leaving] set, [profile] is the owner and its lock takes
     *  the kids profile [leaving] out to the picker (KP-D23). [relockReason]: why a phone re-lock
     *  brought it back, its playback kept (M6-D8). */
    data class Unlock(
        val profile: Profile,
        val entry: UnlockEntry = UnlockEntry(),
        val leaving: Profile? = null,
        val relockReason: RelockReason? = null,
    ) : GateScreen

    /** Kids profile [kid] was chosen while the owner has no lock (KP-D14: a restore brought it):
     *  the owner sets one, then [kid] opens. */
    data class OwnerLockNeeded(val kid: Profile, val step: OwnerLockStep = OwnerLockStep.Ask) : GateScreen

    /** Kids profile [kid]'s time is up for today, or it's bedtime ([time] is [KidsTime.TimesUp] or
     *  [KidsTime.Bedtime]; KP-D8, D26): its shell stays closed until More time, bedtime's end or
     *  the next day, or the owner's Switch profile takes it to the picker (KP-D9). */
    data class TimeStopped(val kid: Profile, val time: KidsTime, val step: StoppedStep = StoppedStep.Statement) : GateScreen

    /** [profile]'s shell is showing; [GateUiState.shellKey] names its saved state. */
    data class Open(val profile: Profile) : GateScreen
}

/** Time's up or bedtime, and the owner's More time and Switch profile from there (`kp-kids`
 *  timesup…switchpicker). */
sealed interface StoppedStep {
    /** What stopped: OK (or BACK) closes Ayn, More time and Switch profile ask for the owner's lock. */
    data object Statement : StoppedStep

    /** The [owner]'s lock for [ask], counted on the kid's own tries (KP-D23). */
    data class Unlock(val owner: Profile, val ask: StoppedAsk, val entry: UnlockEntry = UnlockEntry()) : StoppedStep

    /** How much more ([MORE_TIME_MINUTES]); [limitTomorrow] (minutes, `null` for none) for the
     *  copy. [adding] while the grant saves; [failed] when it didn't. */
    data class Pick(val limitTomorrow: Int?, val adding: Boolean = false, val failed: Boolean = false) : StoppedStep
}

/** What the owner's lock on Time's up is asked for: More time, or leaving for the picker. */
enum class StoppedAsk { MoreTime, SwitchProfile }

/** The More time choices, in minutes (15-minute steps, KP-D8). */
val MORE_TIME_MINUTES: List<Int> = listOf(15, 30, 60)

sealed interface OwnerLockStep {
    /** Why a lock is needed, with Lock my profile and Back. */
    data object Ask : OwnerLockStep

    data object ChooseKind : OwnerLockStep

    /** The new [kind] lock, entered twice. */
    data class NewLock(val kind: LockKind, val entry: UnlockEntry = UnlockEntry()) : OwnerLockStep
}

/** First launch's restore (`kp-backup` firstrestore…firstdone, KP-D24). */
sealed interface RestoreStep {
    data object Reading : RestoreStep

    data class Refused(val reason: BackupRefusal) : RestoreStep

    /** The backup holds no owner profile, so it can't start this device (Q18). */
    data object NoOwner : RestoreStep

    /** "Restore this backup?": [profiles] in file order, the owner first; [deviceSettings]: it
     *  carries a device's settings. [restoring] once Restore everything was pressed. */
    data class Confirm(
        val profiles: List<BackupProfileSummary>,
        val deviceSettings: Boolean,
        val restoring: Boolean = false,
    ) : RestoreStep

    /** Choose profiles: [chosen] indexes [profiles]; the owner's is always in it. The device's
     *  settings come back only when every profile is chosen. */
    data class Choose(
        val profiles: List<BackupProfileSummary>,
        val deviceSettings: Boolean,
        val chosen: Set<Int>,
        val restoring: Boolean = false,
    ) : RestoreStep

    /** The restore failed; nothing on the device changed. Restore tries the same choices again. */
    data object Failed : RestoreStep

    /** "Backup restored" with what came back from a backup exported at [exportedAtMillis]; every
     *  profile arrived unlocked. [lock] while the owner sets a lock from here (Lock my profile);
     *  Done opens the owner. */
    data class Done(val result: RestoreResult.Restored, val lock: OwnerLockStep? = null, val exportedAtMillis: Long = 0) : RestoreStep
}

/**
 * The lock-entry input's state ([LockEntryInput]: unlock, owner gate, setting a lock). [entered]
 * presses or digits are held (never their values); a wrong try or a mismatched new lock clears
 * them and leaves [rejectedDots] showing (shaking) until the UI has shown it; [rejections] counts
 * them so each one shakes. While [wait] runs, input is off.
 */
data class UnlockEntry(
    val entered: Int = 0,
    val message: UnlockMessage? = null,
    val rejectedDots: Int = 0,
    val rejections: Int = 0,
    /** Time left before tries open again, counting down; `null` when not waiting. */
    val wait: Duration? = null,
    /** The whole of the wait as first shown, for the draining ring. */
    val waitShown: Duration? = null,
    /** A try is being checked (or a new lock saved): input is off and nothing cancels it. */
    val checking: Boolean = false,
    /** Setting a lock: the second entry, which must match the first. */
    val confirming: Boolean = false,
)

sealed interface UnlockMessage {
    /** OK with fewer presses than a sequence has; no try counted. */
    data object TooShort : UnlockMessage

    /** [triesBeforeWait] wrong tries are left before one starts a wait. */
    data class Wrong(val triesBeforeWait: Int) : UnlockMessage

    /** Setting a lock: the second entry didn't match the first, so nothing was saved and the
     *  first is asked for again. */
    data object Mismatch : UnlockMessage
}

/** [shellKey] changes whenever a profile's shell opens fresh (0 before the first). [restored] is
 *  set when the shell opened after a Settings restore: it starts on Settings › Backup & restore
 *  with the results, then consumes them (Q9, KP-D17). */
data class GateUiState(
    val screen: GateScreen = GateScreen.Loading,
    val profiles: List<Profile> = emptyList(),
    val shellKey: Int = 0,
    val restored: RestoredBackup? = null,
    /** The profile whose video a phone keeps under the picker or its unlock (M6-D8), as of the last
     *  screen change: the strip shows only while the player still holds the video. Opening another
     *  profile ends it. */
    val keptFor: Profile? = null,
)

/** What a Settings restore brought back, for its results: [result] from [fileName] (as listed or
 *  picked), a backup exported at [exportedAtMillis]. */
data class RestoredBackup(val fileName: String?, val exportedAtMillis: Long, val result: RestoreResult.Restored)
