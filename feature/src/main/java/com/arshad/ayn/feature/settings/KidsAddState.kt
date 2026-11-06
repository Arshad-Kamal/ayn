package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.feature.profiles.UnlockEntry
import java.time.DayOfWeek

/**
 * Adding a kids profile (`kp-kids` nolock, kidform, kidchannels, kidtime; KP-D3, D8, D10, D14):
 * [draft] is what the three steps fill in, kept while the person steps back and forth. [owner]
 * is the device's owner, [ownChannels] the owner's subscriptions (offered on the channels step).
 * [added] is the new profile's id, once, for the Profiles page to focus its row; the UI consumes it.
 */
data class KidsAddUiState(
    val screen: KidsAddScreen = KidsAddScreen.Closed,
    val draft: KidDraft = KidDraft(),
    val owner: Profile? = null,
    val ownChannels: List<Subscription> = emptyList(),
    val added: Long? = null,
)

/** What a new kids profile gets. [channels] are approved, newest first. Bedtime applies when
 *  [bedtimeOn]; its times are kept while it is off. */
data class KidDraft(
    val name: String = "",
    val tint: ProfileTint = ProfileTint.Violet,
    val channels: List<ChannelItem> = emptyList(),
    val dailyLimitMinutes: Map<DayOfWeek, Int> = DEFAULT_DAILY_LIMITS,
    val bedtime: Bedtime = DEFAULT_BEDTIME,
    val bedtimeOn: Boolean = true,
) {
    /** Whether [channelUrl] (any URL form) is approved. */
    fun approves(channelUrl: String): Boolean {
        val key = canonicalChannelUrl(channelUrl)
        return channels.any { canonicalChannelUrl(it.url) == key }
    }
}

/** What shows of the add flow; [Closed] leaves the Profiles page (and its kind sheet) showing. */
sealed interface KidsAddScreen {
    data object Closed : KidsAddScreen

    /** The owner's [lockKind] lock before anything is filled in (M5-D15). */
    data class OwnerGate(val lockKind: LockKind, val entry: UnlockEntry = UnlockEntry()) : KidsAddScreen

    /** The owner has no lock (KP-D14): why, then Choose a lock and the new lock. */
    data class OwnerLockNeeded(val step: OwnerLockStep = OwnerLockStep.Ask) : KidsAddScreen

    /** Step 1: name and colour; a refused name says why. */
    data class Form(val problem: NameProblem? = null) : KidsAddScreen

    /** Step 2: the approved channels; [find] is the Find a channel sheet while it shows. */
    data class Channels(val find: FindChannel? = null) : KidsAddScreen

    /** Step 3: daily limits and bedtime; [sheet] picks a value. [failed]: the last Add didn't save. */
    data class Time(val sheet: TimeSheet? = null, val saving: Boolean = false, val failed: Boolean = false) : KidsAddScreen
}

/** Find a channel: the [query] typed, then what YouTube's search found. */
data class FindChannel(val query: String = "", val results: FindResults = FindResults.Idle)

sealed interface FindResults {
    data object Idle : FindResults

    data object Searching : FindResults

    /** The channels on the search's first page, in YouTube's order. */
    data class Found(val query: String, val channels: List<ChannelItem>) : FindResults

    data class NoMatch(val query: String) : FindResults

    data object Failed : FindResults
}

sealed interface TimeSheet {
    data class DayLimit(val day: DayOfWeek) : TimeSheet

    data object BedtimeStart : TimeSheet

    data object BedtimeEnd : TimeSheet
}

/** The add flow's starting limits (`kp-kids` kidtime): 1 h on weekdays, 1 h 30 min at weekends. */
val DEFAULT_DAILY_LIMITS: Map<DayOfWeek, Int> =
    DayOfWeek.entries.associateWith { if (it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY) 90 else 60 }

/** The add flow's starting bedtime (`kp-kids` kidtime): 7:30 PM to 7:00 AM. */
val DEFAULT_BEDTIME: Bedtime = Bedtime(startMinute = 19 * 60 + 30, endMinute = 7 * 60)

/** A daily limit is picked in [LIMIT_STEP_MINUTES] steps up to [MAX_PICKED_LIMIT_MINUTES] (KP-D8). */
const val LIMIT_STEP_MINUTES = 15
const val MAX_PICKED_LIMIT_MINUTES = 4 * 60

/** Bedtime is picked in [BEDTIME_STEP_MINUTES] steps (KP-D8). */
const val BEDTIME_STEP_MINUTES = 30

/** A day sheet's choices: No limit (`null`), then every step up to the most offered. */
fun dayLimitChoices(): List<Int?> = listOf<Int?>(null) + (LIMIT_STEP_MINUTES..MAX_PICKED_LIMIT_MINUTES step LIMIT_STEP_MINUTES)

/** A bedtime bound's choices, every step of the day but [other] (the other bound's), which would
 *  empty the window. */
fun bedtimeChoices(other: Int): List<Int> = (0..<24 * 60 step BEDTIME_STEP_MINUTES).filter { it != other }

/** A day sheet's pick the rules take: `null` (No limit) or a step up to the most offered. */
fun isPickableLimit(minutes: Int?): Boolean =
    minutes == null || (minutes in LIMIT_STEP_MINUTES..MAX_PICKED_LIMIT_MINUTES && minutes % LIMIT_STEP_MINUTES == 0)

/** A bedtime sheet's pick on a step; [Bedtime.orNull] checks the window itself. */
fun isPickableBedtimeMinute(minute: Int): Boolean = minute % BEDTIME_STEP_MINUTES == 0
