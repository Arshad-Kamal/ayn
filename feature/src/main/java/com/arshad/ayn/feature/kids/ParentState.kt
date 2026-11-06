package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.settings.DEFAULT_BEDTIME
import com.arshad.ayn.feature.settings.Listed
import com.arshad.ayn.feature.settings.TimeSheet
import com.arshad.ayn.feature.settings.listed

/**
 * A kids profile's Parent page (`kp-kids` parentgate, parent, review; KP-D10, D11), opened from
 * the kid's rail: [kid] and the device's [owner], the kid's [rules] (`null` until read) and its
 * approved [channels], most recently approved first.
 */
data class ParentUiState(
    val kid: Profile? = null,
    val owner: Profile? = null,
    val screen: ParentScreen = ParentScreen.Closed,
    val rules: ProfileRules? = null,
    val channels: List<Subscription> = emptyList(),
    /** The bedtime the Bedtime sheet shows while it is off: the last one this page saw, else the
     *  add flow's default. */
    val bedtimeOff: Bedtime = DEFAULT_BEDTIME,
) {
    /** The kid's approved videos, newest first. */
    val videos: List<Listed<ApprovedVideo>> get() = rules.listed<ApprovedVideo>(byOwner = true)
}

sealed interface ParentScreen {
    /** Nothing shows: the page is off screen, or its gate is being read. */
    data object Closed : ParentScreen

    /** The owner's [lockKind] lock, counted on the kid's own tries (KP-D9). */
    data class OwnerGate(val lockKind: LockKind, val entry: UnlockEntry = UnlockEntry()) : ParentScreen

    /** The Parent page, [sheet] over it. */
    data class Page(val sheet: ParentSheet? = null) : ParentScreen

    /** The parent review (F12), over the page. */
    data object Review : ParentScreen
}

/** What shows over the Parent page. */
sealed interface ParentSheet {
    data object ApprovedChannels : ParentSheet

    data object ApprovedVideos : ParentSheet

    /** A row per weekday; OK opens that day's [Time] sheet. */
    data object DailyLimits : ParentSheet

    /** Bedtime's start, end and on/off; OK on a bound opens its [Time] sheet. */
    data object Bedtime : ParentSheet

    /** A day's limit or a bedtime bound to pick (the add flow's sheets). */
    data class Time(val sheet: TimeSheet) : ParentSheet
}
